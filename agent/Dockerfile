# Агент для локальной разработки (docker-compose.dev.yml).
FROM rust:1-slim AS build
WORKDIR /src
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/pulse-agent /usr/local/bin/pulse-agent
ENTRYPOINT ["pulse-agent"]
