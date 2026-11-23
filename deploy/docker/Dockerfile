# NoteHive backend — multi-stage build (non-root runtime)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S notehive && adduser -S notehive -G notehive
WORKDIR /opt/notehive
COPY --from=build /src/target/quarkus-app/ /opt/notehive/
USER notehive
# writable location for the auto-generated DEV JWT keypair (prod: inject PEM/JWKS)
ENV NOTEHIVE_JWT_DEV_KEY_DIR=/tmp/notehive-jwt-keys
EXPOSE 8080
# graceful shutdown: SIGTERM -> quarkus.shutdown.timeout drain (stream consumers
# stop fetching, in-flight handlers finish, unacked entries stay in the PEL)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8080/q/health/ready >/dev/null 2>&1 || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /opt/notehive/quarkus-run.jar"]
