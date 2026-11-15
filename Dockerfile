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
USER user
ENV PORT=8083
ENV JAVA_TOOL_OPTIONS="-Xmx128m -Xmn8m -Xss256k -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
CMD ["/app/jre/bin/java", "-cp", "/app/lib/*", "ee.tenman.auth.MainKt"]
