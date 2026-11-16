FROM ghcr.io/graalvm/native-image-community:25@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0 AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
RUN --mount=type=cache,target=/root/.gradle ./gradlew installDist --no-daemon
RUN native-image -march=compatibility -O2 -Dsun.net.httpserver.maxReqTime=10 --initialize-at-build-time=klite.slf4j.Slf4jRedirectorCreator --initialize-at-run-time=jdk.internal.event.EventHelper -cp "build/install/klite-auth/lib/*" -o auth ee.tenman.auth.MainKt

FROM debian:13-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd -r user
COPY --from=build /app/auth /app/auth
ENV PORT=8083 ENV=prod
USER user
CMD ["/app/auth", "-Xmx128m", "-Xmn8m"]
