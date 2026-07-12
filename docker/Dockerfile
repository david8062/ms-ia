# syntax=docker/dockerfile:1.7
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║  ms-ia · imagen de PRODUCCIÓN optimizada (multi-stage: build → JRE)       ║
# ║  Build:  docker build -f docker/Dockerfile -t iuscloud/ms-ia:prod .       ║
# ╚══════════════════════════════════════════════════════════════════════════╝

# ── Stage 1: compilación con Maven + JDK 21 ───────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package -DskipTests

# ── Stage 2: runtime liviano (solo JRE) ───────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system spring \
 && useradd --system --gid spring --home /app spring

COPY --from=builder /build/target/*.jar app.jar
RUN chown -R spring:spring /app
USER spring

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8087
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
