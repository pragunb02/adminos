FROM python:3.14-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
RUN useradd --system --no-create-home app
USER app

EXPOSE 8000
# 0.0.0.0 inside the container only: compose publishes no port, the API reaches it on the internal network.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
