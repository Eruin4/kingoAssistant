#!/usr/bin/env bash
set -euo pipefail

RECORDINGS_DIR="${RECORDINGS_DIR:-/mnt/backup/home_voice_recordings}"
WAV_RETENTION_DAYS="${WAV_RETENTION_DAYS:-7}"
LOG_FILE="${LOG_FILE:-/home/eruin/infra/cleanup.log}"

mkdir -p "$(dirname "$LOG_FILE")"

{
  echo "[$(date --iso-8601=seconds)] cleanup started"
  if [ -d "$RECORDINGS_DIR" ]; then
    find "$RECORDINGS_DIR" -type f -name '*.wav' -mtime +"$WAV_RETENTION_DAYS" -print -delete
  fi
  docker image prune -f
  docker builder prune -f --filter until=168h
  echo "[$(date --iso-8601=seconds)] cleanup complete"
} >>"$LOG_FILE" 2>&1
