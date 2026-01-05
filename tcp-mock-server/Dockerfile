FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Set configurable dashboard credentials
ENV TCP_MOCK_DASHBOARD_USERNAME=admin
ENV TCP_MOCK_DASHBOARD_PASSWORD=pockethive

COPY .local-jars/tcp-mock-server.jar app.jar
COPY *.md /app/
COPY mappings/ /app/mappings/
COPY __files/ /app/__files/

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
