FROM python:3.12.11-slim-bookworm

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ffmpeg \
        libimage-exiftool-perl \
        libmagic1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --requirement requirements.txt

COPY app.py config.py document_converter.py entrypoint.py llm_clients.py ./

RUN useradd --create-home --uid 10001 parser \
    && chown -R parser:parser /app
USER parser

ENV PYTHONUNBUFFERED=1
EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import os, urllib.request; port=os.getenv('HARNESS_DOCUMENT_PARSER_PORT', '8082'); urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=3).read()"

CMD ["python", "/app/entrypoint.py"]
