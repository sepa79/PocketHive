# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG APP_JAR=target/*.jar
COPY ${APP_JAR} app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
