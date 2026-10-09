# syntax=docker/dockerfile:1
# Backend image: Spring Boot 3.5 on Java 25 (java.version in pom.xml). Built in two stages so the image holds a JRE and
# the jar only: no Maven, no source, no build cache. The build stage needs the internet (Maven Central); nothing here
# does at run time. Context: this folder (backend/). Used by docker-compose.production.yml; see docs/docker-deployment.md.

FROM maven:3.9-eclipse-temurin-25-noble AS build
WORKDIR /build
COPY pom.xml ./
COPY config ./config
COPY src/main ./src/main
# Tests need a MySQL and run in CI / scripts/verify.ps1, not while an image is built.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package \
 && cp target/fams-backend-*.jar /build/app.jar

FROM eclipse-temurin:25-jre-noble
# A fixed, unprivileged account. /data/documents is where issued reports are kept (FAMS_STORAGE_DIR): it belongs to that
# account alone (0750), and a named volume mounted there inherits exactly this ownership the first time it is used.
RUN groupadd --system --gid 10001 fams \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin fams \
 && install -d -o 10001 -g 10001 -m 0750 /data/documents
COPY --from=build /build/app.jar /app/app.jar
USER 10001:10001
WORKDIR /app
ENV FAMS_STORAGE_DIR=/data/documents \
    FAMS_BIND_ADDRESS=0.0.0.0
EXPOSE 8080
# No profile is set: the application starts with its production defaults (never the "dev" profile).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
