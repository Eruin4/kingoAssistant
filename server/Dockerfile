FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1
ENV KINGOGPT_NO_AUTO_REFRESH=1

WORKDIR /app/home_voice_server

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir requests>=2.31

CMD ["python", "/app/home_voice_server/server/home_voice_server.py", "--host", "0.0.0.0", "--port", "8001"]
