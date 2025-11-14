FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash curl \
    && addgroup -S pocket && adduser -S pocket -G pocket

WORKDIR /app
RUN chown pocket:pocket /app
USER pocket

ENV JAVA_TOOL_OPTIONS="" \
    POCKETHIVE_APP_JAR="/app/app.jar"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
