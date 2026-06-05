# kingoAssistant

Android voice scheduler backed by a small HomeVoice HTTP server.

The Android app records Korean voice commands, sends WAV audio to the server,
shows AI-proposed schedule changes, and lets the user accept or reject those
changes before they are applied. Text commands use the same schedule pipeline as
voice commands.

## Current Production Endpoint

```text
https://eruin.mooo.com/voice
```

The Android default server URL is:

```text
https://eruin.mooo.com/voice
```

The public nginx route proxies `/voice/` to the Docker service listening on
`127.0.0.1:8001` on the host.

## Repository Layout

```text
app/                         Android app
server/home_voice_server.py  HTTP API, schedule store, STT, AI command shaping
server/docker-compose.yml    Production Docker service definition
server/Dockerfile            Runtime image for the HomeVoice server
server/test_home_voice_server.py
tools/                       Wake-word sample/model helpers
```

Notable local-only files are ignored by git:

```text
local.properties
server/.env
server/123.txt
server/state/
server/logs/
server/recordings/
native/third_party/
voice/
```

## Android App

Package:

```text
com.example.homeassistantvoice
```

Main features:

- Chat, calendar, task, and settings panes.
- One-tap microphone recording and upload.
- Home-screen microphone widget service.
- Pending proposal review band.
- Swipe-to-delete calendar events and tasks.
- Optional TTS response playback.
- Reminder notifications for scheduled events.

The app stores these settings in shared preferences:

```text
server_url
api_key
alarm_minutes
tts_enabled
```

Build output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This checkout does not include a Gradle wrapper. Build with an installed Gradle
or the Android Studio bundled Gradle setup, using Java 17 and Android SDK 35.

## Server

The server stores schedule state in:

```text
server/state/schedule.json
```

State shape:

```text
calendars.calendar_1
calendars.calendar_2
tasks
proposals
chat_history
updated_at
```

Command flow:

1. Android uploads a 16 kHz mono WAV to `/stt-command`.
2. The server runs `whisper.cpp`.
3. The transcribed text and recent schedule context are sent to the external
   KingoGPT solver mounted at `/app/kingoGPT/kingogpt_api_solver.py`.
4. The solver returns structured JSON such as `propose_add_event`,
   `propose_add_task`, `query_events`, or `question`.
5. Create/delete actions are queued as proposals.
6. The app accepts, rejects, or accepts all proposals.

If the solver is unavailable or returns unusable output, the server falls back
to a conservative local classifier.

## API

Health does not require an API key:

```text
GET /health
```

All other endpoints require:

```text
Authorization: Bearer <HOME_VOICE_API_KEY>
```

Endpoints:

```text
GET    /schedule
POST   /command
POST   /stt-command
POST   /proposal/accept
POST   /proposal/reject
POST   /proposal/accept-all
POST   /event
POST   /task
DELETE /event/{id}
DELETE /task/{id}
```

Text command request:

```json
{"command":"내일 오후 3시에 회의 추가해줘"}
```

Proposal request:

```json
{"proposal_id":"p-20260605123000-abcdef"}
```

WAV upload uses multipart form field:

```text
audio
```

## Production Deploy

Expected host paths:

```text
/home/eruin/home_voice_server
/home/eruin/kingoGPT
/mnt/backup/home_voice_recordings
```

The HomeVoice compose service mounts the app repository at:

```text
/app/home_voice_server
```

and mounts the external KingoGPT checkout at:

```text
/app/kingoGPT
```

Deploy or restart on the server:

```bash
ssh eruin@192.168.0.3
cd /home/eruin/home_voice_server/server
docker compose up -d --build
docker compose logs -f home-voice-server
```

Verify:

```bash
curl https://eruin.mooo.com/voice/health
docker compose -f /home/eruin/home_voice_server/server/docker-compose.yml ps
```

## Required Secrets

Create `/home/eruin/home_voice_server/server/.env` on the server:

```text
HOME_VOICE_API_KEY=replace-me
```

The KingoGPT token cache is provided by the external checkout:

```text
/home/eruin/kingoGPT/state/kingogpt_token_cache.json
```

The container runs with:

```text
KINGOGPT_NO_AUTO_REFRESH=1
```

Refresh the KingoGPT token on the host if server responses report missing,
expired, or unauthorized KingoGPT credentials.

## Whisper

Install `whisper.cpp` under:

```text
native/third_party/whisper.cpp
```

Expected Linux defaults:

```text
native/third_party/whisper.cpp/build/bin/whisper-cli
native/third_party/whisper.cpp/models/ggml-small.bin
```

Example setup:

```bash
cd ~/home_voice_server/native/third_party
git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp
make -j8
./models/download-ggml-model.sh small
```

## Tests

Server tests:

```powershell
py -m unittest server/test_home_voice_server.py
```

Android build requires Gradle and the Android SDK:

```powershell
gradle assembleDebug
```

## Privacy

Do not commit API keys, `.env` files, KingoGPT token/config/profile data,
recordings, logs, Whisper model binaries, native build outputs, or APK build
artifacts.
