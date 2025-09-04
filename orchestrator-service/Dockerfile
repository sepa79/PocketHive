FROM openjdk:21-jre-slim

WORKDIR /app

COPY target/orchestrator-service-*.jar app.jar

EXPOSE 8091

ENTRYPOINT ["java", "-jar", "app.jar"]