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

| | klite-auth (native image) | auth (Spring, native image) |
|---|---|---|
| Startup | 0.3 s | 0.8–0.9 s |
| 27,000 requests | 2.0–2.1 s | 9.3–11.5 s |
| Memory after load (anon) | 11 MiB | 23 MiB |
| Image | 49 MB | 73 MB |
| Kotlin, main / test | 277 / 266 lines | 299 / 185 lines |

Five runs each, on an Apple Silicon Mac, 2026-11-15.

## Native image

The Docker image is a GraalVM native image. `src/main/resources/META-INF/native-image/ee.tenman/klite-auth/reachability-metadata.json`
lists what Klite reaches by reflection. It was recorded by running the tests under GraalVM's tracing agent, keeping only the
app's entries (not Gradle's, JUnit's or the test classes'). Record it again after adding a class that Klite builds by
reflection, such as a new JSON body, or the native image fails on that path while the JVM tests still pass.
