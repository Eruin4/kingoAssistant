# Runtime Snapshot

Captured from `eruin@192.168.0.3` on 2026-06-04 16:22 UTC.

## Host

- Hostname: `eruinmcserver`
- Uptime at capture: `105 days, 8:34`
- Public service domain: `https://eruin.mooo.com`
- HomeVoice health endpoint: `https://eruin.mooo.com/voice/health`

## Running Docker Containers

| Container | Image | Status | Ports |
| --- | --- | --- | --- |
| `home-voice-server` | `server-home-voice-server` | `Up 6 days` | `127.0.0.1:8001->8001/tcp` |
| `eruin_nginx` | `nginx:1.25-alpine` | `Up 7 days` | `0.0.0.0:80->80/tcp`, `0.0.0.0:443->443/tcp` |
| `hermingo` | `hermingo:latest` | `Up 9 days (healthy)` | `127.0.0.1:8000->8000/tcp` |

## Stopped Bamboo Containers

These are intentionally stopped:

| Container | Image | Status |
| --- | --- | --- |
| `bamboo_frontend` | `infra-frontend` | `Exited (0)` |
| `bamboo_backend` | `infra-backend` | `Exited (0)` |
| `bamboo_postgres` | `postgres:16-alpine` | `Exited (0)` |
| `bamboo_redis` | `redis:7-alpine` | `Exited (0)` |

## Active Compose Projects

| Project | Status | Compose file |
| --- | --- | --- |
| `infra` | `running(1)` | `/home/eruin/infra/docker-compose.yml` |
| `server` | `running(1)` | `/home/eruin/home_voice_server/server/docker-compose.yml` |
| `hermingo` | `running(1)` | `/home/eruin/hermingo/docker-compose.yml` |

## Compose Summary

### `infra`

- Container: `eruin_nginx`
- Image: `nginx:1.25-alpine`
- Restart policy: `unless-stopped`
- Ports: `80:80`, `443:443`
- Networks:
  - `infra_bamboo_internal`
  - `server_default`
- Important mounts:
  - `/home/eruin/infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro`
  - `/home/eruin/infra/nginx/conf.d:/etc/nginx/conf.d:ro`
  - `/home/eruin/infra/nginx/certbot:/var/www/certbot:ro`
  - `/home/eruin/infra/nginx/letsencrypt:/etc/letsencrypt:ro`
  - `infra_nginx_logs:/var/log/nginx`

### `server`

- Container: `home-voice-server`
- Build context: `/home/eruin/home_voice_server`
- Dockerfile: `server/Dockerfile`
- Restart policy: `unless-stopped`
- Port: `127.0.0.1:8001:8001`
- Important environment:
  - `HOME_VOICE_API_KEY` from `.env`
  - `KINGOGPT_NO_AUTO_REFRESH=1`
  - `KINGOGPT_SOLVER=/app/kingoGPT/kingogpt_api_solver.py`
  - `KINGOGPT_TOKEN_CACHE=/app/kingoGPT/state/kingogpt_token_cache.json`
  - `HOME_VOICE_RECORDINGS_DIR=/data/home_voice_recordings`
- Important mounts:
  - `/home/eruin/home_voice_server:/app/home_voice_server`
  - `/home/eruin/kingoGPT:/app/kingoGPT`
  - `/mnt/backup/home_voice_recordings:/data/home_voice_recordings`

### `hermingo`

- Container: `hermingo`
- Image: `hermingo:latest`
- Restart policy: `unless-stopped`
- Port: `127.0.0.1:8000:8000`
- Health: healthy
- Important mounts:
  - `/home/eruin/kingoGPT/state:/app/state`
  - `/home/eruin/kingoGPT/kingogpt:/app/kingogpt:ro`

## Network And Ports

Listening ports at capture:

- `0.0.0.0:22` SSH
- `0.0.0.0:80` nginx HTTP
- `0.0.0.0:443` nginx HTTPS
- `127.0.0.1:8000` hermingo
- `127.0.0.1:8001` HomeVoice server

Docker networks:

- `hermingo_default`
- `infra_bamboo_internal`
- `kingogpt_default`
- `server_default`
- default Docker networks: `bridge`, `host`, `none`

Docker volumes:

- `infra_nginx_logs`
- `infra_postgres_data`
- `infra_redis_data`

## Nginx Current Behavior

- `eruin.mooo.com` on HTTP redirects to HTTPS.
- `eruin.mooo.com` on HTTPS serves:
  - `/health`
  - `/voice/` proxied to `home-voice-server:8001`
- Old bamboo hostnames are blocked with nginx `444`.
- Direct HTTP IP `/voice/` fallback is not enabled.

## Restart Commands After Power Cycle

Normally these restart automatically because all three active services use `restart: unless-stopped`.

Manual recovery:

```bash
ssh eruin@192.168.0.3

cd /home/eruin/home_voice_server/server
docker compose up -d

cd /home/eruin/infra
docker compose up -d

cd /home/eruin/hermingo
docker compose up -d
```

Verify:

```bash
docker compose -f /home/eruin/home_voice_server/server/docker-compose.yml ps
docker compose -f /home/eruin/infra/docker-compose.yml ps
docker compose -f /home/eruin/hermingo/docker-compose.yml ps
curl https://eruin.mooo.com/voice/health
```

Keep bamboo stopped:

```bash
docker stop bamboo_frontend bamboo_backend bamboo_postgres bamboo_redis 2>/dev/null || true
```
