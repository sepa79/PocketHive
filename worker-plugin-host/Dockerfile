# ---- Build host jar ----
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY . .

RUN mvn -q -B -DskipTests -pl worker-plugin-host -am package

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV POCKETHIVE_PLUGIN_HOST_PLUGIN_DIR=/opt/pockethive/plugins \
    POCKETHIVE_PLUGIN_HOST_OVERRIDES_DIR=/app/config/plugins

RUN mkdir -p "$POCKETHIVE_PLUGIN_HOST_PLUGIN_DIR" "$POCKETHIVE_PLUGIN_HOST_OVERRIDES_DIR"
VOLUME ["${POCKETHIVE_PLUGIN_HOST_PLUGIN_DIR}", "${POCKETHIVE_PLUGIN_HOST_OVERRIDES_DIR}"]

COPY --from=build /app/worker-plugin-host/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
