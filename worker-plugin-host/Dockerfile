FROM eclipse-temurin:21-jre-alpine AS base
LABEL org.opencontainers.image.source="https://github.com/pockethive/pockethive"

ARG JAR=worker-plugin-host-0.0.1-SNAPSHOT.jar
ENV POCKETHIVE_PLUGIN_DIR=/opt/pockethive/plugins
ENV JAVA_OPTS=""

RUN addgroup -S pockethive && adduser -S pockethive -G pockethive

WORKDIR /opt/pockethive
RUN mkdir -p plugins config && chown -R pockethive:pockethive /opt/pockethive

COPY dist/hosts/worker-plugin-host.jar app.jar

USER pockethive
ENTRYPOINT ["/bin/sh", "-c", "java $JAVA_OPTS -jar /opt/pockethive/app.jar"]
