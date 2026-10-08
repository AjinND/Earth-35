FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY download.py .
RUN python download.py && rm -rf /root/.cache/huggingface
COPY server.py .
USER 10001
HEALTHCHECK --interval=5s --timeout=3s --retries=20 CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/health',timeout=2)"
CMD ["python", "server.py"]
