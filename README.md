# kingoAssistant

Android voice assistant plus a local Docker server.

Flow:

1. Android records a 16 kHz mono WAV.
2. Android uploads the WAV to `http://192.168.0.3:8001/stt-command`.
3. The Docker server runs `whisper.cpp` STT.
4. The STT text is sent to `kingogpt_api_solver.py`.
5. The KingoGPT answer is returned as JSON `response`.
6. Android reads `response` with Android TTS.

## Android

Project path:

```text
app/
```

Build:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Server

Server files:

```text
server/
```

Docker compose:

```bash
cd ~/home_voice_server/server
docker compose up -d --build
docker compose logs -f
```

The server listens on:

```text
0.0.0.0:8001
```

Endpoints:

```text
GET  /health
POST /command
POST /stt-command
```

`/stt-command` accepts multipart form field `audio` containing WAV data.

## Required Server Volumes

The compose file expects these host paths:

```text
/home/eruin/home_voice_server
/home/eruin/kingoGPT
```

The following files are intentionally not committed:

```text
kingogpt_token_cache.json
kingogpt_config.json
kingogpt_chrome_profile*
whisper.cpp checkout
ggml-*.bin model files
server logs and recordings
```

Install `whisper.cpp` and a model on the server, for example:

```bash
cd ~/home_voice_server/native/third_party
git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp
make -j8
./models/download-ggml-model.sh small
```

The server defaults to:

```text
native/third_party/whisper.cpp/build/bin/whisper-cli
native/third_party/whisper.cpp/models/ggml-small.bin
```

## Privacy

No KingoGPT token cache, login config, browser profile, recordings, logs, Whisper model binaries, or build artifacts should be committed.
