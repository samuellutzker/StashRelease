# ── Build stage ───────────────────────────────────────────────────────────────
FROM rust:1-slim-bookworm AS builder

WORKDIR /build

# Pre-cache dependencies before copying source (invalidated only by Cargo.toml/lock changes).
COPY server/Cargo.toml server/Cargo.lock ./
RUN mkdir src && echo 'fn main(){}' > src/main.rs \
 && cargo build --release \
 && rm -rf src

# Copy real source + templates (templates are embedded via include_str! at compile time).
COPY server/src ./src
COPY server/templates ./templates
RUN touch src/main.rs && cargo build --release

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM debian:bookworm-slim

# ca-certificates needed for outbound TLS (mirror connections to peers with public certs).
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/release/stash-server /usr/local/bin/stash-server
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# /data holds the database, uploaded files, TLS certs, and the config file.
VOLUME /data

# 9876 — sync WebSocket (ws:// or wss://)
# 9877 — web UI (HTTP)
EXPOSE 9876 9877

ENTRYPOINT ["/entrypoint.sh"]
