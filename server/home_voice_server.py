from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import uuid
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parents[1]
RECORDINGS = Path(os.environ.get("HOME_VOICE_RECORDINGS_DIR", ROOT / "server" / "recordings"))
STATE_DIR = Path(os.environ.get("HOME_VOICE_STATE_DIR", ROOT / "server" / "state"))
API_KEY = os.environ.get("HOME_VOICE_API_KEY", "").strip()
SCHEDULE_STATE = STATE_DIR / "schedule.json"
DEFAULT_WHISPER_EXE = ROOT / "native" / "third_party" / "whisper.cpp" / "build" / "bin" / "Release" / "whisper-cli.exe"
DEFAULT_WHISPER_EXE_LINUX = ROOT / "native" / "third_party" / "whisper.cpp" / "build" / "bin" / "whisper-cli"
DEFAULT_MODEL = ROOT / "native" / "third_party" / "whisper.cpp" / "models" / "ggml-small.bin"
DEFAULT_KINGOGPT_SOLVER = Path(os.environ.get("KINGOGPT_SOLVER", "/app/kingoGPT/kingogpt_api_solver.py"))
DEFAULT_KINGOGPT_TOKEN_CACHE = Path(os.environ.get("KINGOGPT_TOKEN_CACHE", "/app/kingoGPT/state/kingogpt_token_cache.json"))
DEFAULT_KINGOGPT_TOKEN_CONFIG = Path(os.environ.get("KINGOGPT_TOKEN_CONFIG", "/app/kingoGPT/state/kingogpt_config.json"))
DEFAULT_KINGOGPT_PROFILE_DIR = Path(os.environ.get("KINGOGPT_PROFILE_DIR", "/app/kingoGPT/state/kingogpt_chrome_profile_debug"))


PROPOSE_ACTIONS = {
    "propose_add_event": "add_event",
    "propose_add_task": "add_task",
    "propose_delete_event": "delete_event",
    "propose_delete_task": "delete_task",
}
APPLY_ACTIONS = {"add_event", "add_task", "delete_event", "delete_task"}
QUERY_ACTIONS = {"query_events", "query_tasks", "question", "message", "list"}


def now_id() -> str:
    return datetime.now().strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]


def json_clone(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False))


class ScheduleStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.Lock()
        self.state = self._load()

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            if self._cleanup_past_events_locked():
                self._save_locked()
            return json_clone(self.state)

    def handle_ai_command(self, command: dict[str, Any], *, user_text: str = "") -> dict[str, Any]:
        with self.lock:
            action = str(command.get("action", "")).strip()
            if action in PROPOSE_ACTIONS:
                proposal = self._proposal_from_command(command, PROPOSE_ACTIONS[action])
                self.state["proposals"].append(proposal)
                message = str(command.get("message") or proposal["message"]).strip()
                self._append_history("user", user_text)
                self._append_history("ai", message, action=action, payload=command)
                self._touch_save_locked()
                return self._response(message, False, command)

            if action in APPLY_ACTIONS:
                changed, message = self._apply_locked(command)
                self._append_history("user", user_text)
                self._append_history("ai", message, action=action, payload=command)
                self._touch_save_locked()
                return self._response(message, changed, command)

            message = self._query_message(command)
            self._append_history("user", user_text)
            self._append_history("ai", message, action=action or "message", payload=command)
            self._touch_save_locked()
            return self._response(message, False, command)

    def add_event(self, payload: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            command = dict(payload)
            command["action"] = "add_event"
            changed, message = self._apply_locked(command)
            self._touch_save_locked()
            return self._response(message, changed, command)

    def add_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            command = dict(payload)
            command["action"] = "add_task"
            changed, message = self._apply_locked(command)
            self._touch_save_locked()
            return self._response(message, changed, command)

    def delete_event(self, event_id: str) -> dict[str, Any]:
        with self.lock:
            command = {"action": "delete_event", "id": event_id}
            changed, message = self._apply_locked(command)
            self._touch_save_locked()
            return self._response(message, changed, command)

    def delete_task(self, task_id: str) -> dict[str, Any]:
        with self.lock:
            command = {"action": "delete_task", "id": task_id}
            changed, message = self._apply_locked(command)
            self._touch_save_locked()
            return self._response(message, changed, command)

    def accept_proposal(self, proposal_id: str) -> dict[str, Any]:
        with self.lock:
            proposal = self._pop_proposal(proposal_id)
            if proposal is None:
                return self._response("Proposal not found.", False, {"proposal_id": proposal_id}, status="missing")
            changed, message = self._apply_locked(proposal)
            self._append_history("system", f"Accepted proposal {proposal_id}", action="accept_proposal", payload=proposal)
            self._touch_save_locked()
            return self._response(message, changed, proposal, status="accepted")

    def reject_proposal(self, proposal_id: str) -> dict[str, Any]:
        with self.lock:
            proposal = self._pop_proposal(proposal_id)
            if proposal is None:
                return self._response("Proposal not found.", False, {"proposal_id": proposal_id}, status="missing")
            self._append_history("system", f"Rejected proposal {proposal_id}", action="reject_proposal", payload=proposal)
            self._touch_save_locked()
            return self._response("Proposal rejected.", True, proposal, status="rejected")

    def accept_all(self) -> dict[str, Any]:
        with self.lock:
            proposals = list(self.state["proposals"])
            self.state["proposals"] = []
            applied = 0
            for proposal in proposals:
                changed, _message = self._apply_locked(proposal)
                if changed:
                    applied += 1
            self._append_history("system", f"Accepted {applied} proposals", action="accept_all", payload={"count": applied})
            self._touch_save_locked()
            return self._response(f"Accepted {applied} proposals.", applied > 0, {"action": "accept_all", "count": applied})

    def _load(self) -> dict[str, Any]:
        if self.path.is_file():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    state = self._normalize(data)
                    self._cleanup_past_events(state)
                    return state
            except json.JSONDecodeError:
                pass
        return self._normalize({})

    def _normalize(self, data: dict[str, Any]) -> dict[str, Any]:
        calendars = data.get("calendars") if isinstance(data.get("calendars"), dict) else {}
        legacy_calendar_1 = data.get("calendar_1") if isinstance(data.get("calendar_1"), list) else []
        legacy_calendar_2 = data.get("calendar_2") if isinstance(data.get("calendar_2"), list) else []
        return {
            "calendars": {
                "calendar_1": self._normalize_events(list(calendars.get("calendar_1") or legacy_calendar_1 or []), "calendar_1"),
                "calendar_2": self._normalize_events(list(calendars.get("calendar_2") or legacy_calendar_2 or []), "calendar_2"),
            },
            "tasks": self._normalize_tasks(list(data.get("tasks") or [])),
            "proposals": self._normalize_proposals(list(data.get("proposals") or [])),
            "chat_history": self._normalize_history(list(data.get("chat_history") or [])),
            "updated_at": data.get("updated_at") or datetime.now().isoformat(timespec="seconds"),
        }

    def _normalize_events(self, events: list[Any], calendar: str) -> list[dict[str, Any]]:
        normalized = []
        for item in events:
            if isinstance(item, dict):
                event = dict(item)
                event["id"] = str(event.get("id") or now_id())
                event["calendar"] = str(event.get("calendar") or calendar)
                event.setdefault("created_at", datetime.now().isoformat(timespec="seconds"))
                normalized.append(event)
        return normalized

    def _normalize_tasks(self, tasks: list[Any]) -> list[dict[str, Any]]:
        normalized = []
        for item in tasks:
            if isinstance(item, dict):
                task = dict(item)
                task["id"] = str(task.get("id") or now_id())
                task["done"] = bool(task.get("done", False))
                task.setdefault("created_at", datetime.now().isoformat(timespec="seconds"))
                normalized.append(task)
        return normalized

    def _normalize_proposals(self, proposals: list[Any]) -> list[dict[str, Any]]:
        return [dict(item) for item in proposals if isinstance(item, dict)]

    def _normalize_history(self, history: list[Any]) -> list[dict[str, Any]]:
        return [dict(item) for item in history[-50:] if isinstance(item, dict)]

    def _proposal_from_command(self, command: dict[str, Any], apply_action: str) -> dict[str, Any]:
        proposal = dict(command)
        proposal["proposal_id"] = str(command.get("proposal_id") or "p-" + now_id())
        proposal["action"] = apply_action
        proposal["created_at"] = datetime.now().isoformat(timespec="seconds")
        if apply_action == "add_event":
            proposal.update(self._event_from_command(proposal))
        elif apply_action == "add_task":
            proposal.update(self._task_from_command(proposal))
        message = str(command.get("message") or "").strip()
        if not message:
            message = self._default_message(apply_action, proposal, proposed=True)
        proposal["message"] = message
        return proposal

    def _apply_locked(self, command: dict[str, Any]) -> tuple[bool, str]:
        action = str(command.get("action", "")).strip()
        changed = False
        if action == "add_event":
            event = self._event_from_command(command)
            self.state["calendars"].setdefault(event["calendar"], []).append(event)
            changed = True
        elif action == "add_task":
            self.state["tasks"].append(self._task_from_command(command))
            changed = True
        elif action == "delete_event":
            changed = self._delete_event_locked(command)
        elif action == "delete_task":
            changed = self._delete_task_locked(command)
        elif action == "complete_task":
            changed = self._delete_task_locked(command)
        else:
            return False, "I could not understand that schedule action."

        changed = self._cleanup_past_events_locked() or changed
        message = str(command.get("message") or "").strip() or self._default_message(action, command, changed=changed)
        return changed, message

    def _query_message(self, command: dict[str, Any]) -> str:
        action = str(command.get("action") or "message")
        message = str(command.get("message") or "").strip()
        if message:
            return message
        if action in ("query_events", "list"):
            total = sum(len(events) for events in self.state["calendars"].values())
            return f"There are {total} calendar events."
        if action == "query_tasks":
            return f"There are {len(self.state['tasks'])} tasks."
        if action == "question":
            return "I need one more detail before I can make that schedule change."
        return "No schedule change was made."

    def _default_message(self, action: str, command: dict[str, Any], *, proposed: bool = False, changed: bool = True) -> str:
        title = str(command.get("title") or "Untitled").strip()
        if proposed:
            if action == "add_event":
                return f'Proposed calendar event "{title}".'
            if action == "add_task":
                return f'Proposed task "{title}".'
            if action == "delete_event":
                return f'Proposed deleting event "{title}".'
            if action == "delete_task":
                return f'Proposed deleting task "{title}".'
        if action == "add_event":
            return f'Added calendar event "{title}".'
        if action == "add_task":
            return f'Added task "{title}".'
        if action == "delete_event":
            return "Deleted event." if changed else "Event not found."
        if action in ("delete_task", "complete_task"):
            return "Deleted task." if changed else "Task not found."
        return "Schedule updated." if changed else "No schedule change was made."

    def _event_from_command(self, command: dict[str, Any]) -> dict[str, Any]:
        calendar = str(command.get("calendar", "calendar_1")).strip()
        if calendar not in ("calendar_1", "calendar_2"):
            calendar = "calendar_1"
        return {
            "id": str(command.get("id") or now_id()),
            "calendar": calendar,
            "title": str(command.get("title") or "Untitled").strip(),
            "date": str(command.get("date") or "").strip(),
            "start_time": str(command.get("start_time") or "").strip(),
            "end_time": str(command.get("end_time") or "").strip(),
            "memo": str(command.get("memo") or "").strip(),
            "created_at": str(command.get("created_at") or datetime.now().isoformat(timespec="seconds")),
        }

    def _task_from_command(self, command: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": str(command.get("id") or now_id()),
            "title": str(command.get("title") or "Untitled").strip(),
            "memo": str(command.get("memo") or "").strip(),
            "done": bool(command.get("done", False)),
            "created_at": str(command.get("created_at") or datetime.now().isoformat(timespec="seconds")),
        }

    def _delete_event_locked(self, command: dict[str, Any]) -> bool:
        event_id = str(command.get("id") or "").strip()
        title = str(command.get("title") or "").strip()
        changed = False
        for key, events in self.state["calendars"].items():
            filtered = []
            for event in events:
                if event_id and str(event.get("id")) == event_id:
                    changed = True
                elif title and title in str(event.get("title", "")):
                    changed = True
                else:
                    filtered.append(event)
            self.state["calendars"][key] = filtered
        return changed

    def _delete_task_locked(self, command: dict[str, Any]) -> bool:
        task_id = str(command.get("id") or "").strip()
        title = str(command.get("title") or "").strip()
        before = len(self.state["tasks"])
        self.state["tasks"] = [
            task for task in self.state["tasks"]
            if not ((task_id and str(task.get("id")) == task_id) or (title and title in str(task.get("title", ""))))
        ]
        return len(self.state["tasks"]) != before

    def _pop_proposal(self, proposal_id: str) -> dict[str, Any] | None:
        remaining = []
        found = None
        for proposal in self.state["proposals"]:
            if str(proposal.get("proposal_id")) == proposal_id:
                found = proposal
            else:
                remaining.append(proposal)
        self.state["proposals"] = remaining
        return found

    def _append_history(self, role: str, text: str, *, action: str | None = None, payload: dict[str, Any] | None = None) -> None:
        if not text and not action:
            return
        item: dict[str, Any] = {"role": role, "text": text, "timestamp": datetime.now().isoformat(timespec="seconds")}
        if action:
            item["action"] = action
        if payload:
            item["payload"] = payload
        self.state["chat_history"].append(item)
        self.state["chat_history"] = self.state["chat_history"][-50:]

    def _cleanup_past_events_locked(self) -> bool:
        return self._cleanup_past_events(self.state)

    def _cleanup_past_events(self, state: dict[str, Any]) -> bool:
        today = date.today()
        changed = False
        for key, events in state["calendars"].items():
            current = [event for event in events if self._is_current_or_future_event(event, today)]
            current.sort(key=self._event_sort_key)
            changed = changed or len(current) != len(events) or current != events
            state["calendars"][key] = current
        if changed:
            state["updated_at"] = datetime.now().isoformat(timespec="seconds")
        return changed

    def _is_current_or_future_event(self, event: dict[str, Any], today: date) -> bool:
        event_date = self._parse_date(str(event.get("date") or ""))
        if event_date is not None:
            return (today - event_date).days <= 14
        created_date = self._parse_datetime_date(str(event.get("created_at") or ""))
        return created_date is None or (today - created_date).days <= 14

    def _event_sort_key(self, event: dict[str, Any]) -> tuple[str, str, str]:
        return (
            str(event.get("date") or "9999-12-31"),
            str(event.get("start_time") or "99:99"),
            str(event.get("title") or ""),
        )

    def _parse_date(self, value: str) -> date | None:
        if not value:
            return None
        try:
            return date.fromisoformat(value[:10])
        except ValueError:
            return None

    def _parse_datetime_date(self, value: str) -> date | None:
        if not value:
            return None
        try:
            return datetime.fromisoformat(value).date()
        except ValueError:
            return self._parse_date(value)

    def _touch_save_locked(self) -> None:
        self.state["updated_at"] = datetime.now().isoformat(timespec="seconds")
        self._cleanup_past_events_locked()
        self._save_locked()

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.state, ensure_ascii=False, indent=2), encoding="utf-8")

    def _response(
        self,
        message: str,
        applied: bool,
        command: dict[str, Any],
        *,
        status: str = "ok",
    ) -> dict[str, Any]:
        return {
            "response": message,
            "applied": applied,
            "status": status,
            "structured_command": command,
            "schedule": json_clone(self.state),
        }


def json_bytes(payload: dict[str, Any], status: int = 200) -> tuple[int, bytes, str]:
    return status, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8"


def build_voice_prompt(user_text: str, schedule: dict[str, Any]) -> str:
    today = datetime.now().date().isoformat()
    return (
        "Convert the user's Korean voice/text schedule request into one JSON object only.\n"
        "Do not return Markdown or explanation.\n"
        "Allowed actions: propose_add_event, propose_add_task, propose_delete_event, propose_delete_task, "
        "query_events, query_tasks, question, message.\n"
        "Schema: {\"action\":\"...\",\"calendar\":\"calendar_1|calendar_2\",\"title\":\"...\","
        "\"date\":\"YYYY-MM-DD\",\"start_time\":\"HH:MM\",\"end_time\":\"HH:MM\",\"memo\":\"...\","
        "\"id\":\"existing item id for deletes\",\"message\":\"short Korean response for TTS\"}\n"
        "Use propose_* for all create/delete changes. Use query_* for schedule lookups. "
        "Use question if required date/time/title details are missing.\n"
        f"Today: {today}\n"
        f"Current schedule: {json.dumps(schedule, ensure_ascii=False)}\n"
        f"User input: {user_text}"
    )


def extract_json_object(text: str) -> dict[str, Any] | None:
    text = text.strip()
    if not text:
        return None
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.IGNORECASE | re.DOTALL).strip()
    candidates = [text]
    match = re.search(r"\{.*\}", text, flags=re.DOTALL)
    if match:
        candidates.append(match.group(0))
    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            continue
    return None


def fallback_structured_command(command: str) -> dict[str, Any]:
    text = command.strip()
    if not text:
        return {"action": "question", "message": "말씀하신 내용을 듣지 못했습니다."}
    if any(word in text for word in ("할 일", "작업", "task", "todo", "투두")):
        return {"action": "propose_add_task", "title": text, "message": "할 일 추가를 제안했습니다."}
    if any(word in text for word in ("삭제", "지워", "빼줘", "완료")):
        return {"action": "propose_delete_task", "title": text, "message": "삭제를 제안했습니다."}
    if any(word in text for word in ("확인", "보여", "목록", "뭐")):
        return {"action": "query_events", "message": "현재 일정을 확인했습니다."}
    calendar = "calendar_2" if "2" in text or "두 번째" in text else "calendar_1"
    return {"action": "propose_add_event", "calendar": calendar, "title": text, "message": "일정 추가를 제안했습니다."}


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
        "home-schedule",
    ]
    if server.kingogpt_no_auto_refresh:  # type: ignore[attr-defined]
        cmd.append("--no-auto-refresh-token")
    if server.kingogpt_keep_chat_thread:  # type: ignore[attr-defined]
        cmd.append("--keep-chat-thread")
    cmd.append(prompt)

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
        if capture or line:
            lines.append(line)
    return "\n".join(lines).strip()


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
    server_version = "HomeVoiceServer/2.0"

    def check_auth(self) -> bool:
        if not API_KEY:
            return True
        auth = self.headers.get("Authorization", "")
        if auth == f"Bearer {API_KEY}":
            return True
        self.send_payload(*json_bytes({"error": "Unauthorized"}, 401))
        return False

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/health":
            self.send_payload(*json_bytes({"ok": True, "time": datetime.now().isoformat(timespec="seconds")}))
            return
        if not self.check_auth():
            return
        if path == "/schedule":
            self.send_payload(*json_bytes({"schedule": self.server.schedule_store.snapshot()}))  # type: ignore[attr-defined]
            return
        self.send_payload(*json_bytes({"error": "Not found"}, 404))

    def do_POST(self) -> None:
        if not self.check_auth():
            return
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        if path == "/command":
            self.handle_command(body)
            return
        if path == "/stt-command":
            self.handle_stt_command(body)
            return
        if path == "/proposal/accept":
            self.handle_accept(body)
            return
        if path == "/proposal/reject":
            self.handle_reject(body)
            return
        if path == "/proposal/accept-all":
            self.send_payload(*json_bytes(self.server.schedule_store.accept_all()))  # type: ignore[attr-defined]
            return
        if path == "/event":
            self.send_payload(*json_bytes(self.server.schedule_store.add_event(self._json_body(body))))  # type: ignore[attr-defined]
            return
        if path == "/task":
            self.send_payload(*json_bytes(self.server.schedule_store.add_task(self._json_body(body))))  # type: ignore[attr-defined]
            return
        self.send_payload(*json_bytes({"error": "Not found"}, 404))

    def do_DELETE(self) -> None:
        if not self.check_auth():
            return
        path = urlparse(self.path).path
        if path.startswith("/task/"):
            task_id = unquote(path.removeprefix("/task/"))
            self.send_payload(*json_bytes(self.server.schedule_store.delete_task(task_id)))  # type: ignore[attr-defined]
            return
        if path.startswith("/event/"):
            event_id = unquote(path.removeprefix("/event/"))
            self.send_payload(*json_bytes(self.server.schedule_store.delete_event(event_id)))  # type: ignore[attr-defined]
            return
        self.send_payload(*json_bytes({"error": "Not found"}, 404))

    def _json_body(self, body: bytes) -> dict[str, Any]:
        try:
            payload = json.loads(body.decode("utf-8"))
            return payload if isinstance(payload, dict) else {}
        except Exception:
            return {}

    def handle_command(self, body: bytes) -> None:
        payload = self._json_body(body)
        command = str(payload.get("command", "")) if payload else body.decode("utf-8", errors="replace")
        response = self.structure_command(command)
        self.send_payload(*json_bytes(response))

    def handle_accept(self, body: bytes) -> None:
        proposal_id = str(self._json_body(body).get("proposal_id") or "")
        self.send_payload(*json_bytes(self.server.schedule_store.accept_proposal(proposal_id)))  # type: ignore[attr-defined]

    def handle_reject(self, body: bytes) -> None:
        proposal_id = str(self._json_body(body).get("proposal_id") or "")
        self.send_payload(*json_bytes(self.server.schedule_store.reject_proposal(proposal_id)))  # type: ignore[attr-defined]

    def structure_command(self, command: str, *, stt_error: str | None = None) -> dict[str, Any]:
        store: ScheduleStore = self.server.schedule_store  # type: ignore[attr-defined]
        raw_ai = ""
        kingogpt_error = None
        structured = None
        if command.strip():
            raw_ai, kingogpt_error = run_kingogpt(build_voice_prompt(command, store.snapshot()), self.server)
            structured = extract_json_object(raw_ai)
        if structured is None:
            structured = fallback_structured_command(command)
        response = store.handle_ai_command(structured, user_text=command)
        response.update({"command": command, "ai_raw": raw_ai, "kingogpt_error": kingogpt_error, "stt_error": stt_error})
        return response

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

        wav_deleted = False
        response: dict[str, Any] = {}
        try:
            text, error = transcribe_wav(
                wav_path,
                self.server.model_path,  # type: ignore[attr-defined]
                self.server.whisper_exe,  # type: ignore[attr-defined]
                self.server.whisper_timeout,  # type: ignore[attr-defined]
            )
            response = self.structure_command(text, stt_error=error)
            response.update({"stt": text, "wav": str(wav_path), "wav_deleted": False})
        finally:
            try:
                wav_path.unlink(missing_ok=True)
                wav_deleted = True
            except OSError as exc:
                sys.stdout.write(f"{datetime.now().isoformat(timespec='seconds')} failed to delete WAV {wav_path}: {exc}\n")
                sys.stdout.flush()
        response["wav_deleted"] = wav_deleted
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
    parser.add_argument("--schedule-state", default=str(SCHEDULE_STATE))
    parser.add_argument("--kingogpt-solver", default=str(DEFAULT_KINGOGPT_SOLVER))
    parser.add_argument("--kingogpt-token-cache", default=str(DEFAULT_KINGOGPT_TOKEN_CACHE))
    parser.add_argument("--kingogpt-token-config", default=str(DEFAULT_KINGOGPT_TOKEN_CONFIG))
    parser.add_argument("--kingogpt-profile-dir", default=str(DEFAULT_KINGOGPT_PROFILE_DIR))
    parser.add_argument("--kingogpt-timeout", type=int, default=180)
    parser.add_argument("--kingogpt-no-auto-refresh", action="store_true", default=os.environ.get("KINGOGPT_NO_AUTO_REFRESH", "1") != "0")
    parser.add_argument("--kingogpt-keep-chat-thread", action="store_true", default=os.environ.get("KINGOGPT_KEEP_CHAT_THREAD", "0") == "1")
    args = parser.parse_args()

    httpd = ThreadingHTTPServer((args.host, args.port), HomeVoiceHandler)
    httpd.model_path = Path(args.model)  # type: ignore[attr-defined]
    httpd.whisper_exe = find_whisper_exe(args.whisper_exe)  # type: ignore[attr-defined]
    httpd.whisper_timeout = args.whisper_timeout  # type: ignore[attr-defined]
    httpd.schedule_store = ScheduleStore(Path(args.schedule_state))  # type: ignore[attr-defined]
    httpd.kingogpt_solver = Path(args.kingogpt_solver)  # type: ignore[attr-defined]
    httpd.kingogpt_token_cache = Path(args.kingogpt_token_cache)  # type: ignore[attr-defined]
    httpd.kingogpt_token_config = Path(args.kingogpt_token_config)  # type: ignore[attr-defined]
    httpd.kingogpt_profile_dir = Path(args.kingogpt_profile_dir)  # type: ignore[attr-defined]
    httpd.kingogpt_timeout = args.kingogpt_timeout  # type: ignore[attr-defined]
    httpd.kingogpt_no_auto_refresh = args.kingogpt_no_auto_refresh  # type: ignore[attr-defined]
    httpd.kingogpt_keep_chat_thread = args.kingogpt_keep_chat_thread  # type: ignore[attr-defined]

    print(f"Listening on http://{args.host}:{args.port}")
    print(f"Model: {httpd.model_path}")
    print(f"Whisper CLI: {httpd.whisper_exe or 'missing'}")
    print(f"Schedule state: {args.schedule_state}")
    print(f"KingoGPT solver: {httpd.kingogpt_solver}")
    print(f"API Key: {'configured (' + API_KEY[:8] + '...)' if API_KEY else 'disabled (no auth)'}")
    print(
        "Endpoints: GET /health, GET /schedule, POST /command, POST /stt-command, "
        "POST /proposal/accept, POST /proposal/reject, POST /proposal/accept-all, "
        "POST /event, POST /task, DELETE /event/{id}, DELETE /task/{id}"
    )
    httpd.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
