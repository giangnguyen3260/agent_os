# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 agentos \
    && useradd --uid 10001 --gid agentos --no-create-home --shell /usr/sbin/nologin agentos
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/agentos.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/agentos.jar"]
