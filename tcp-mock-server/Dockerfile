FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Set configurable dashboard credentials
ENV TCP_MOCK_DASHBOARD_USERNAME=admin
ENV TCP_MOCK_DASHBOARD_PASSWORD=admin

COPY target/tcp-mock-server-*.jar app.jar
COPY docs/*.md /app/docs/
COPY mappings/ /app/mappings/
COPY __files/ /app/__files/
COPY scenarios-state.json /app/scenarios-state-default.json

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
