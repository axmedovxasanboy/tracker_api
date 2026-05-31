# syntax=docker/dockerfile:1.7

# ─── Build stage ──────────────────────────────────────────────────────────────
# Spring Boot 4.0.5 requires Java 21+; pom.xml pins Java 25. Eclipse Temurin only
# ships JDK images for 25 (no -jre-alpine variant yet), so we use the JDK in both
# stages. Final image is ~340 MB; acceptable on a 4 GB VPS.
FROM eclipse-temurin:25.0.3_9-jre-alpine AS build
WORKDIR /workspace

# Maven wrapper + manifest first → deps layer survives source-only changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Now the source
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package \
 && cp target/trackerproject-*.jar app.jar

# ─── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25.0.3_9-jre-alpine AS runtime

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/app.jar app.jar

USER app

EXPOSE 8080

# Container-aware JVM. MaxRAMPercentage works when the cgroup memory limit is set
# on the container; -Xmx512m is a hard ceiling regardless, leaving headroom for
# the other containers on the 4 GB host.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -Xmx512m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
