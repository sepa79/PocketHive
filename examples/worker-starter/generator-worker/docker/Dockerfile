# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
COPY generator-worker/pom.xml ./generator-worker/pom.xml
COPY generator-worker/src ./generator-worker/src

ARG MAVEN_ARGS=""

RUN apk add --no-cache maven \
    && mvn -B -q -pl generator-worker -am package $MAVEN_ARGS

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/generator-worker/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
