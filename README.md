# klite-auth

The [auth](https://github.com/ktenman/auth) service ported to [Klite](https://github.com/keksworks/klite): Google sign-in
for the fov.ee sites, sessions in Redis, and the `/validate` endpoint that Caddy's `forward_auth` asks on every request.

## Tests

```bash
docker compose up -d   # Redis on localhost:6380
./gradlew test
```

## Configuration

| Variable | |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | OAuth client from the Google Console; its redirect URI is `<REDIRECT_URI>/login/oauth2/code/google` |
| `REDIRECT_URI` | Where users land after signing in, e.g. `https://fov.ee` |
| `ALLOWED_EMAILS` | Comma-separated Google accounts that may sign in |
| `REDIS_URL` | Default `redis://redis:6379` |
| `PORT` | 8083 in the Docker image |

## Compared with the Spring version

`bench/run.sh` builds the image and runs it on 2 CPUs next to Redis and a fake Google. It checks that sign-in, validate and sign-out
still work, sends 27,000 requests (mostly `/validate`, as Caddy's `forward_auth` does), then reads the container's memory.
`IMAGE=ktenman/auth:latest bench/run.sh` measures the Spring version the same way.

| | klite-auth (JVM, AOT cache) | auth (Spring, native image) |
|---|---|---|
| Startup | 0.9–1.0 s | 0.8–1.0 s |
| 27,000 requests | 2.2–2.5 s | 10.7–12.3 s |
| Memory after load (anon) | 64–66 MiB | 23 MiB |
| Image | 48 MB | 73 MB |
| Kotlin, main / test | 133 / 236 lines | 299 / 185 lines |

Three runs each, on an Apple Silicon Mac, 2026-11-15.
