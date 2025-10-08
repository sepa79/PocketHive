# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
COPY processor-worker/pom.xml ./processor-worker/pom.xml
COPY processor-worker/src ./processor-worker/src

ARG MAVEN_ARGS=""

RUN apk add --no-cache maven \
    && mvn -B -q -pl processor-worker -am package $MAVEN_ARGS

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/processor-worker/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
