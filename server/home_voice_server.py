from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
RECORDINGS = ROOT / "server" / "recordings"
DEFAULT_WHISPER_EXE = ROOT / "native" / "third_party" / "whisper.cpp" / "build" / "bin" / "Release" / "whisper-cli.exe"
DEFAULT_WHISPER_EXE_LINUX = ROOT / "native" / "third_party" / "whisper.cpp" / "build" / "bin" / "whisper-cli"
DEFAULT_MODEL = ROOT / "native" / "third_party" / "whisper.cpp" / "models" / "ggml-small.bin"
DEFAULT_KINGOGPT_SOLVER = Path(os.environ.get("KINGOGPT_SOLVER", "/app/kingoGPT/kingogpt_api_solver.py"))
DEFAULT_KINGOGPT_TOKEN_CACHE = Path(os.environ.get("KINGOGPT_TOKEN_CACHE", "/app/kingoGPT/state/kingogpt_token_cache.json"))
DEFAULT_KINGOGPT_TOKEN_CONFIG = Path(os.environ.get("KINGOGPT_TOKEN_CONFIG", "/app/kingoGPT/state/kingogpt_config.json"))
DEFAULT_KINGOGPT_PROFILE_DIR = Path(os.environ.get("KINGOGPT_PROFILE_DIR", "/app/kingoGPT/state/kingogpt_chrome_profile_debug"))


def json_bytes(payload: dict[str, Any], status: int = 200) -> tuple[int, bytes, str]:
    return status, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8"


def fallback_command_response(command: str) -> dict[str, Any]:
    command = command.strip()
    if not command:
        return {"response": "No command text.", "command": command}
    return {
        "response": f"Command received: {command}",
        "command": command,
    }


def run_kingogpt(prompt: str, server: ThreadingHTTPServer) -> tuple[str, str | None]:
    prompt = prompt.strip()
    if not prompt:
        return "", "Missing prompt for KingoGPT."

    solver_path: Path = server.kingogpt_solver  # type: ignore[attr-defined]
    if not solver_path.is_file():
        return "", f"Missing kingoGPT solver: {solver_path}"

    cmd = [
        sys.executable,
        str(solver_path),
        "--token-cache",
        str(server.kingogpt_token_cache),  # type: ignore[attr-defined]
        "--token-config",
        str(server.kingogpt_token_config),  # type: ignore[attr-defined]
        "--profile-dir",
        str(server.kingogpt_profile_dir),  # type: ignore[attr-defined]
        "--request-timeout",
        str(server.kingogpt_timeout),  # type: ignore[attr-defined]
        "--session-key",
        "home-voice",
    ]
    if server.kingogpt_no_auto_refresh:  # type: ignore[attr-defined]
        cmd.append("--no-auto-refresh-token")
        cmd.append("--ignore-expiry")
    cmd.append(build_voice_prompt(prompt))

    try:
        proc = subprocess.run(
            cmd,
            cwd=str(solver_path.parent),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=int(server.kingogpt_timeout) + 30,  # type: ignore[attr-defined]
            check=False,
        )
    except subprocess.TimeoutExpired:
        return "", f"KingoGPT timed out after {server.kingogpt_timeout}s"  # type: ignore[attr-defined]

    answer = parse_kingogpt_output(proc.stdout)
    if proc.returncode != 0 and not answer:
        return "", proc.stdout.strip() or f"KingoGPT failed with exit code {proc.returncode}"
    return answer, None


def parse_kingogpt_output(output: str) -> str:
    lines: list[str] = []
    capture = False
    skip_next_warning_detail = False
    for raw in output.splitlines():
        line = raw.strip()
        if not line:
            continue
        if skip_next_warning_detail:
            skip_next_warning_detail = False
            continue
        if line.startswith("[*] API response started"):
            capture = True
            continue
        if line.startswith("---"):
            continue
        if line.startswith("[") and "]" in line[:8]:
            continue
        if "DeprecationWarning:" in line or line.startswith("File "):
            skip_next_warning_detail = True
            continue
        if "<!-- tools:" in line:
            line = line.split("<!-- tools:", 1)[0].strip()
        if capture or line:
            lines.append(line)
    return clean_for_tts("\n".join(lines).strip())


def build_voice_prompt(user_text: str) -> str:
    return (
        "다음 문장은 사용자가 휴대폰 음성으로 말한 내용입니다.\n"
        "한국어로 자연스럽게 답하세요. 휴대폰 TTS가 읽을 답변이므로 마크다운, 목록, 이모지, HTML 주석, 코드블록은 쓰지 마세요.\n"
        "특별히 길게 설명할 필요가 없으면 1~3문장으로 짧게 답하세요.\n\n"
        f"사용자 음성 입력: {user_text}"
    )


def clean_for_tts(text: str) -> str:
    text = re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)
    text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    text = re.sub(r"^#{1,6}\s*", "", text, flags=re.MULTILINE)
    text = re.sub(r"^\s*[-*]\s+", "", text, flags=re.MULTILINE)
    text = re.sub(r"\*\*(.*?)\*\*", r"\1", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    text = re.sub(r"[\U0001F000-\U0001FAFF\u2600-\u27BF]", "", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def find_whisper_exe(configured: str | None) -> Path | None:
    candidates = []
    if configured:
        candidates.append(Path(configured))
    candidates.append(DEFAULT_WHISPER_EXE)
    candidates.append(DEFAULT_WHISPER_EXE_LINUX)
    path_value = os.environ.get("PATH", "")
    for part in path_value.split(os.pathsep):
        if part:
            candidates.append(Path(part) / "whisper-cli.exe")
            candidates.append(Path(part) / "main.exe")
            candidates.append(Path(part) / "whisper.exe")
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def transcribe_wav(wav_path: Path, model_path: Path, whisper_exe: Path | None, timeout_sec: int) -> tuple[str, str | None]:
    if not model_path.is_file():
        return "", f"Missing model: {model_path}"
    if whisper_exe is None:
        return "", f"Missing whisper CLI. Expected: {DEFAULT_WHISPER_EXE}"

    cmd = [
        str(whisper_exe),
        "-m",
        str(model_path),
        "-f",
        str(wav_path),
        "-l",
        "ko",
        "-nt",
        "-t",
        str(max(1, min(8, (os.cpu_count() or 4) - 1))),
    ]
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(ROOT),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout_sec,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return "", f"Whisper timed out after {timeout_sec}s"

    text = parse_whisper_output(proc.stdout)
    if proc.returncode != 0 and not text:
        return "", proc.stdout.strip() or f"Whisper failed with exit code {proc.returncode}"
    return text, None


def parse_whisper_output(output: str) -> str:
    lines: list[str] = []
    for raw in output.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("whisper_") or line.startswith("system_info:") or line.startswith("main:"):
            continue
        cleaned = re.sub(r"^\[[^\]]+\]\s*", "", line).strip()
        if cleaned:
            lines.append(cleaned)
    return " ".join(lines).strip()


def parse_multipart(content_type: str, body: bytes) -> dict[str, bytes]:
    match = re.search(r"boundary=(?P<boundary>[^;]+)", content_type)
    if not match:
        return {}
    boundary = match.group("boundary").strip().strip('"').encode("utf-8")
    fields: dict[str, bytes] = {}
    for part in body.split(b"--" + boundary):
        part = part.strip()
        if not part or part == b"--":
            continue
        if part.endswith(b"--"):
            part = part[:-2].strip()
        header_blob, sep, data = part.partition(b"\r\n\r\n")
        if not sep:
            continue
        headers = header_blob.decode("utf-8", errors="replace")
        name_match = re.search(r'name="([^"]+)"', headers)
        if not name_match:
            continue
        if data.endswith(b"\r\n"):
            data = data[:-2]
        fields[name_match.group(1)] = data
    return fields


class HomeVoiceHandler(BaseHTTPRequestHandler):
    server_version = "HomeVoiceServer/1.0"

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self.send_payload(*json_bytes({"ok": True, "time": datetime.now().isoformat(timespec="seconds")}))
            return
        self.send_payload(*json_bytes({"error": "Not found"}, 404))

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        if path == "/command":
            self.handle_command(body)
            return
        if path == "/stt-command":
            self.handle_stt_command(body)
            return
        self.send_payload(*json_bytes({"error": "Not found"}, 404))

    def handle_command(self, body: bytes) -> None:
        try:
            payload = json.loads(body.decode("utf-8"))
            command = str(payload.get("command", ""))
        except Exception:
            command = body.decode("utf-8", errors="replace")
        answer, error = run_kingogpt(command, self.server)
        response = {"response": answer, "command": command, "kingogpt_error": error}
        if not answer:
            response.update(fallback_command_response(command))
            response["kingogpt_error"] = error
        self.send_payload(*json_bytes(response))

    def handle_stt_command(self, body: bytes) -> None:
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" in content_type:
            fields = parse_multipart(content_type, body)
            audio = fields.get("audio") or fields.get("file") or b""
        else:
            audio = body

        if not audio:
            self.send_payload(*json_bytes({"error": "Missing WAV audio"}, 400))
            return

        RECORDINGS.mkdir(parents=True, exist_ok=True)
        wav_path = RECORDINGS / f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}.wav"
        wav_path.write_bytes(audio)

        text, error = transcribe_wav(
            wav_path,
            self.server.model_path,  # type: ignore[attr-defined]
            self.server.whisper_exe,  # type: ignore[attr-defined]
            self.server.whisper_timeout,  # type: ignore[attr-defined]
        )
        answer = ""
        kingogpt_error = None
        if text:
            answer, kingogpt_error = run_kingogpt(text, self.server)
        response = {
            "response": answer or kingogpt_error or error or "No answer.",
            "command": text,
            "stt": text,
            "wav": str(wav_path),
            "stt_error": error,
            "kingogpt_error": kingogpt_error,
        }
        self.send_payload(*json_bytes(response))

    def send_payload(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stdout.write(f"{datetime.now().isoformat(timespec='seconds')} {self.address_string()} {fmt % args}\n")
        sys.stdout.flush()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--model", default=str(DEFAULT_MODEL))
    parser.add_argument("--whisper-exe", default=os.environ.get("WHISPER_EXE"))
    parser.add_argument("--whisper-timeout", type=int, default=300)
    parser.add_argument("--kingogpt-solver", default=str(DEFAULT_KINGOGPT_SOLVER))
    parser.add_argument("--kingogpt-token-cache", default=str(DEFAULT_KINGOGPT_TOKEN_CACHE))
    parser.add_argument("--kingogpt-token-config", default=str(DEFAULT_KINGOGPT_TOKEN_CONFIG))
    parser.add_argument("--kingogpt-profile-dir", default=str(DEFAULT_KINGOGPT_PROFILE_DIR))
    parser.add_argument("--kingogpt-timeout", type=int, default=180)
    parser.add_argument("--kingogpt-no-auto-refresh", action="store_true", default=os.environ.get("KINGOGPT_NO_AUTO_REFRESH", "1") != "0")
    args = parser.parse_args()

    httpd = ThreadingHTTPServer((args.host, args.port), HomeVoiceHandler)
    httpd.model_path = Path(args.model)  # type: ignore[attr-defined]
    httpd.whisper_exe = find_whisper_exe(args.whisper_exe)  # type: ignore[attr-defined]
    httpd.whisper_timeout = args.whisper_timeout  # type: ignore[attr-defined]
    httpd.kingogpt_solver = Path(args.kingogpt_solver)  # type: ignore[attr-defined]
    httpd.kingogpt_token_cache = Path(args.kingogpt_token_cache)  # type: ignore[attr-defined]
    httpd.kingogpt_token_config = Path(args.kingogpt_token_config)  # type: ignore[attr-defined]
    httpd.kingogpt_profile_dir = Path(args.kingogpt_profile_dir)  # type: ignore[attr-defined]
    httpd.kingogpt_timeout = args.kingogpt_timeout  # type: ignore[attr-defined]
    httpd.kingogpt_no_auto_refresh = args.kingogpt_no_auto_refresh  # type: ignore[attr-defined]

    print(f"Listening on http://{args.host}:{args.port}")
    print(f"Model: {httpd.model_path}")
    print(f"Whisper CLI: {httpd.whisper_exe or 'missing'}")
    print(f"KingoGPT solver: {httpd.kingogpt_solver}")
    print("Endpoints: GET /health, POST /command, POST /stt-command")
    httpd.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
