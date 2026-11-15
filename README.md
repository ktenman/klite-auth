# klite-auth

The [auth](https://github.com/ktenman/auth) service ported to [Klite](https://github.com/keksworks/klite): Google sign-in
for the fov.ee sites, sessions in Redis, and the `/validate` endpoint that Caddy's `forward_auth` asks on every request.

## Tests

```bash
docker compose up -d   # Redis on localhost:6380
./gradlew test
```
