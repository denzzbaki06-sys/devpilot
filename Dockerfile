# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package
# CodeChunker requires the JDK compiler API at runtime (parse only).
RUN jlink --add-modules java.se,jdk.compiler,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.zipfs,jdk.management,jdk.naming.dns,jdk.charsets \
    --strip-debug --no-man-pages --no-header-files --compress=2 --output /opt/java-runtime

FROM ubuntu:22.04 AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 devpilot && useradd --uid 10001 --gid devpilot --no-create-home devpilot
COPY --from=build /opt/java-runtime /opt/java-runtime
ENV JAVA_HOME=/opt/java-runtime
ENV PATH="/opt/java-runtime/bin:${PATH}"
WORKDIR /app
COPY --from=build --chown=devpilot:devpilot /build/target/devpilot-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
