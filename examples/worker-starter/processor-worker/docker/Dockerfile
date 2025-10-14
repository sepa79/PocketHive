# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY common ./common
COPY observability ./observability
COPY generator-service ./generator-service
COPY log-aggregator-service ./log-aggregator-service
COPY moderator-service ./moderator-service
COPY orchestrator-service ./orchestrator-service
COPY postprocessor-service ./postprocessor-service
COPY processor-service ./processor-service
COPY swarm-controller-service ./swarm-controller-service
COPY trigger-service ./trigger-service
COPY scenario-manager-service ./scenario-manager-service
COPY e2e-tests ./e2e-tests
COPY test ./test
COPY docs ./docs
COPY examples/worker-starter ./examples/worker-starter

ARG MAVEN_ARGS=""

RUN apk add --no-cache maven \
    && mvn -B -q -pl common/worker-sdk -am install $MAVEN_ARGS \
    && mvn -B -q -f examples/worker-starter/pom.xml -pl processor-worker -am package $MAVEN_ARGS

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/examples/worker-starter/processor-worker/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
