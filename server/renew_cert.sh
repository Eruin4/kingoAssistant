#!/usr/bin/env bash
set -euo pipefail

LOG_FILE="${LOG_FILE:-/home/eruin/infra/renew_cert.log}"
NGINX_CONTAINER="${NGINX_CONTAINER:-eruin_nginx}"
LETSENCRYPT_DIR="${LETSENCRYPT_DIR:-/home/eruin/infra/nginx/letsencrypt}"
CERTBOT_WEBROOT="${CERTBOT_WEBROOT:-/home/eruin/infra/nginx/certbot}"
CERTBOT_IMAGE="${CERTBOT_IMAGE:-certbot/certbot:latest}"
CERT_NAME="${CERT_NAME:-eruin.mooo.com}"
RENEW_TIMEOUT="${RENEW_TIMEOUT:-45m}"

mkdir -p "$(dirname "$LOG_FILE")"

{
  echo "[$(date --iso-8601=seconds)] starting certbot renewal"
  timeout "$RENEW_TIMEOUT" docker run --rm \
    -v "$LETSENCRYPT_DIR:/etc/letsencrypt" \
    -v "$CERTBOT_WEBROOT:/var/www/certbot" \
    "$CERTBOT_IMAGE" renew --cert-name "$CERT_NAME" --webroot -w /var/www/certbot --no-random-sleep-on-renew --quiet
  docker exec "$NGINX_CONTAINER" nginx -s reload
  echo "[$(date --iso-8601=seconds)] renewal check complete"
} >>"$LOG_FILE" 2>&1
