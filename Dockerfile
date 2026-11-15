FROM amazoncorretto:25-alpine AS build
WORKDIR /app
RUN apk add --no-cache binutils # objcopy, for jlink --strip-debug
# java.sql is left out: only Gson's optional date adapters (via Jedis) use it
RUN jlink --output jre --add-modules java.net.http,jdk.httpserver,jdk.management \
    --compress=zip-6 --no-header-files --no-man-pages --strip-debug
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
RUN ./gradlew installDist --no-daemon

FROM alpine:3.22
RUN apk add --no-cache curl && adduser -S user
COPY --from=build /app/jre /app/jre
COPY --from=build /app/build/install/klite-auth/lib /app/lib
ENV PORT=8083
ENV JAVA_TOOL_OPTIONS="-Xmx128m -Xmn8m -Xss256k -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
# A training run: later starts map the classes it loaded from this AOT cache, ~12 MiB less memory than loading them
RUN GOOGLE_CLIENT_ID=x GOOGLE_CLIENT_SECRET=x REDIRECT_URI=x ALLOWED_EMAILS=x \
    /app/jre/bin/java -XX:AOTCacheOutput=/app/auth.aot -cp "/app/lib/*" ee.tenman.auth.MainKt & \
    until curl -s -o /dev/null localhost:8083/login; do sleep 0.2; done; \
    curl -s -o /dev/null localhost:8083/validate; curl -s -o /dev/null localhost:8083/missing; \
    kill $! && wait $!; test -f /app/auth.aot
USER user
CMD ["/app/jre/bin/java", "-XX:AOTCache=/app/auth.aot", "-cp", "/app/lib/*", "ee.tenman.auth.MainKt"]
