FROM openjdk:21-jre-slim

WORKDIR /app

COPY target/orchestrator-service-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]