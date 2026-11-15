FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
RUN --mount=type=cache,target=/root/.gradle ./gradlew installDist --no-daemon
RUN native-image -march=compatibility -Os --initialize-at-build-time=klite.slf4j.Slf4jRedirectorCreator --initialize-at-run-time=jdk.internal.event.EventHelper -cp "build/install/klite-auth/lib/*" -o auth ee.tenman.auth.MainKt

FROM debian:13-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd -r user
COPY --from=build /app/auth /app/auth
ENV PORT=8083 ENV=prod
USER user
CMD ["/app/auth", "-Xmx128m", "-Xmn8m"]
