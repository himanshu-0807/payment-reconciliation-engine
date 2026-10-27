# syntax=docker/dockerfile:1.7
# Multi-stage build shared by the three Spring Boot services. Build context: repository root.
#   docker build -f docker/java-service.Dockerfile --build-arg MODULE=recon-service -t recon/recon-service .
ARG MODULE=recon-service

FROM maven:3.9.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
# All module POMs are needed for the reactor; ~/.m2 is a BuildKit cache mount, so dependencies are
# downloaded once and reused across builds.
COPY pom.xml ./
COPY common/pom.xml common/
COPY gateway-sim/pom.xml gateway-sim/
COPY bank-sim/pom.xml bank-sim/
COPY recon-service/pom.xml recon-service/
COPY common/src common/src
COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl ${MODULE} -am -DskipTests package \
 && cp ${MODULE}/target/${MODULE}.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
