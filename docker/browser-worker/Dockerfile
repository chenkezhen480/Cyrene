FROM mcr.microsoft.com/playwright/python:v1.61.0-noble

WORKDIR /app
RUN pip install --no-cache-dir playwright==1.61.0
COPY app.py security.py ./

ENV PYTHONUNBUFFERED=1
EXPOSE 8081

CMD ["python", "/app/app.py"]
