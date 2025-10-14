# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY common ./common
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
