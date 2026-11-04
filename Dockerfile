# Build the jar first (ci/run.sh verify), then: docker build -t mcp-server-adapter:dev .
# Not an Alpine/musl base: mcp-cedar-entities loads the native cedar-java library, built for glibc.
FROM eclipse-temurin:25-jre@sha256:78f1f183913482ef390c2722f46c3f0ce76ada341d69590e7c482ec943ee72d5
LABEL org.opencontainers.image.source="https://github.com/aiwcorpo/mcp-server-adapter" \
      org.opencontainers.image.licenses="Apache-2.0"

# A numeric user, so that a runtime policy (runAsNonRoot) can verify it. A volume for /app/state must be writable by uid 10001.
RUN groupadd --system --gid 10001 mcp && useradd --system --uid 10001 --gid mcp --no-create-home --home-dir /app mcp
WORKDIR /app
COPY --chown=root:root target/mcp-server-adapter.jar /app/app.jar

USER 10001:10001
# mTLS from the gateway only (C3); certificates, policy keys and secrets come from the environment and mounted files.
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
