# The embed service: one Python process holding BGE-small in memory.
FROM python:3.11-slim

# FASTEMBED_CACHE_PATH is where fastembed looks for (and downloads) model files. Pointing
# it inside the image is what lets the model be baked in below.
ENV PYTHONUNBUFFERED=1 \
    FASTEMBED_CACHE_PATH=/app/model_cache \
    HF_HUB_DISABLE_TELEMETRY=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Download the model at build time, so it ships inside the image.
#
# Cloud Run's filesystem is memory: anything written at runtime counts against the
# instance's memory limit. Downloading ~130 MB on startup would sit on top of the ~330 MB
# the process itself uses, and would also repeat on every cold start. Baked in, a cold
# start is just the model load, and startup no longer depends on Hugging Face being up.
RUN python -c "from fastembed import TextEmbedding; TextEmbedding(model_name='BAAI/bge-small-en-v1.5')"

COPY embed_service.py .

RUN useradd --uid 1001 --no-create-home app && chown -R app /app
USER app

# Set only after the download above: the model is on disk now, so the service must never
# reach for the network to load it.
ENV HF_HUB_OFFLINE=1

# Cloud Run injects PORT; embed_service.py reads it.
EXPOSE 8080
CMD ["python", "embed_service.py"]
