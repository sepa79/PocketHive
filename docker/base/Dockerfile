FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash curl

WORKDIR /app

ENV JAVA_TOOL_OPTIONS="" \
    POCKETHIVE_APP_JAR="/app/app.jar"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
