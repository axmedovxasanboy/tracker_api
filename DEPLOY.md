# Deploy — tracker-project (backend)

Built and pushed to `ghcr.io/<owner>/tracker-project` on every push to `main`,
then pulled on the server over SSH.

Service name on the server: **`backend`** (used by `docker compose pull/up`).

---

## GitHub Actions secrets

| Secret           | Purpose                                                |
| ---------------- | ------------------------------------------------------ |
| `SERVER_HOST`    | Hetzner VPS hostname or IP                             |
| `SERVER_USER`    | SSH user — `deploy`                                    |
| `SERVER_SSH_KEY` | Private SSH key (full PEM including header/footer)     |

`GITHUB_TOKEN` is provided automatically and is used to push to ghcr.io.

## Server prerequisites

- Docker + Compose v2 installed on Ubuntu 24.04.
- `~/app/docker-compose.yml` exists with a `backend` service (see below).
- The server is logged in to ghcr.io if the package is **private** (default
  for new packages). One-off:
  ```bash
  echo "$GHCR_PAT" | docker login ghcr.io -u <github-user> --password-stdin
  ```
  Use a fine-grained PAT with `read:packages` scope.

## Compose entry (server-side, in `~/app/docker-compose.yml`)

```yaml
services:
  backend:
    image: ghcr.io/<owner>/tracker-project:latest
    container_name: backend
    restart: unless-stopped
    networks: [app-network]
    environment:
      DB_URL: jdbc:postgresql://172.17.0.1:5432/tracker_db
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      CORS_ALLOWED_ORIGINS: https://your.public.domain
      APP_CARD_ENCRYPTION_KEY: ${APP_CARD_ENCRYPTION_KEY}
      APP_JWT_SECRET: ${APP_JWT_SECRET}
      # Optional overrides — defaults are sensible
      # SERVER_PORT: 8080
      # APP_JWT_ACCESS_TTL: 900
      # APP_JWT_REFRESH_TTL: 604800
      # CARD_REVEAL_MAX_ATTEMPTS: 5
      # CARD_REVEAL_LOCKOUT_SECONDS: 300
      # APP_MAX_PAGE_SIZE: 100

networks:
  app-network:
    external: true
```

The backend reaches Postgres on the host at `172.17.0.1:5432`. The frontend and
bot reach the backend by service name at `http://backend:8080`. Don't publish
ports — Caddy handles the public edge.

> **Stable secrets.** Keep `APP_CARD_ENCRYPTION_KEY` constant per environment —
> rotating it makes every previously-encrypted card number unreadable.
> Rotating `APP_JWT_SECRET` just forces re-login.

## Manual trigger

From the Actions tab → "build-and-deploy" → "Run workflow", or via CLI:

```bash
gh workflow run build-and-deploy.yml --ref main
```

## Roll back

Every successful build pushes two tags: `:latest` and `:<short-sha>`. To pin to
a known-good earlier build, edit `~/app/docker-compose.yml` on the server:

```yaml
    image: ghcr.io/<owner>/tracker-project:a1b2c3d   # ← previous short SHA
```

then:

```bash
cd ~/app
docker compose pull backend
docker compose up -d backend
```

List recent tags from your workstation:

```bash
gh api /users/<owner>/packages/container/tracker-project/versions \
  --jq '.[] | {tags: .metadata.container.tags, created: .created_at}'
```
