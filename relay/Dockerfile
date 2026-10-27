# Релей для локальной разработки и self-hosted деплоя.
FROM rust:1-slim AS build
WORKDIR /src
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/pulse-relay /usr/local/bin/pulse-relay
ENV PULSE_BIND=0.0.0.0:8080
EXPOSE 8080
ENTRYPOINT ["pulse-relay"]
