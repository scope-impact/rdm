FROM rdm:latest
RUN apk add --no-cache g++ cmake make && \
    pip install --break-system-packages duckdb && \
    apk del g++ cmake make
