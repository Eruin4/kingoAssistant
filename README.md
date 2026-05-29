# kingoAssistant

Android schedule assistant plus a local Docker server.

Flow:

1. Android shows chat, combined calendar, and sequential task panes.
2. Tap the mic button once to start recording and again to send the command.
3. Android uploads a 16 kHz mono WAV to `http://192.168.0.3:8001/stt-command`.
4. The Docker server runs `whisper.cpp` STT.
5. The STT text is sent to `kingogpt_api_solver.py` with the current schedule.
6. KingoGPT returns one structured JSON command such as `propose_add_event` or `propose_add_task`.
7. The server stores create/delete commands in a proposal queue in `server/state/schedule.json`.
8. Android shows chat, calendar, and task panes, plus a pending-proposal review band.
9. Accepted proposals are applied to the calendars or task list; rejected proposals are discarded.

Manual text commands can also be sent from the app. They use the same
`/command` path and update the same schedule state.

The schedule model is intentionally small:

- `calendars.calendar_1`: first calendar events.
- `calendars.calendar_2`: second calendar events.
- `tasks`: non-calendar work items processed in list order.
- `proposals`: pending AI-suggested changes.
- `chat_history`: recent user and assistant messages.

Each voice or text input is treated as a loose natural-language request. The AI
normalizes it into a structured proposal or query response, and the server
stores the result.

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
printf 'HOME_VOICE_API_KEY=replace-me\n' > .env
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
GET  /schedule
POST /command
POST /stt-command
POST /proposal/accept
POST /proposal/reject
POST /proposal/accept-all
POST /event
POST /task
DELETE /event/{id}
DELETE /task/{id}
```

`/stt-command` accepts multipart form field `audio` containing WAV data.
`/command` accepts JSON:

```json
{"command":"add a meeting to calendar 1 tomorrow at 10 AM"}
```

Both command paths return the current `schedule` object with `calendars`,
`tasks`, `proposals`, and `chat_history`.

Proposal endpoints accept JSON:

```json
{"proposal_id":"p-20260529120000-abcdef"}
```

`DELETE /task/{id}` removes the task immediately. The app uses this for
left-swipe task completion.

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
server/state/schedule.json
```

## KingoGPT Token

The Docker server uses the token cache mounted from:

```text
/home/eruin/kingoGPT/state/kingogpt_token_cache.json
```

If the app response says the access token is missing, expired, or auth failed,
refresh that cache on the host side before restarting the server. The server is
configured with `KINGOGPT_NO_AUTO_REFRESH=1`, so it does not try to run browser
login inside the slim Docker container.

KingoGPT chat threads are deleted by default after each successful request. To
keep them for debugging, run the server with:

```text
KINGOGPT_KEEP_CHAT_THREAD=1
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
