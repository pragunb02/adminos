# Pinned by digest (AOS-622); Dependabot proposes updates.
FROM python:3.14-slim@sha256:a2b82f3c48559aa0a8446d9af49826b6e2b2016f4cd2afabfe6013ec53729170

# No .pyc files (the root filesystem is read-only at run time), unbuffered logs.
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
# OCR for the documents vault (AOS-717): Tesseract 5.5 and its English data from Debian (signed by the archive, patched
# with the base image), held to the 5.5 line the eval baseline was measured on. No recommends, no package lists left.
RUN apt-get update \
    && apt-get install -y --no-install-recommends "tesseract-ocr=5.5.*" "tesseract-ocr-eng=1:4.1.*" \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --version
# Every transitive dependency pinned with its SHA-256 (requirements.txt, compiled by lock.sh): a substituted package fails the build.
COPY requirements.txt .
RUN pip install --no-cache-dir --require-hashes --no-deps -r requirements.txt \
    && python -m pip uninstall -y -q pip setuptools wheel   # no package manager at run time (AOS-622)

COPY . .
RUN useradd --system --no-create-home app
USER app

EXPOSE 8000
# 0.0.0.0 inside the container only: compose publishes no port, the API reaches it on the internal network.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
