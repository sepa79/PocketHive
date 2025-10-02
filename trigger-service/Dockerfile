# ---- Build (multi-module from repo root) ----
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /app
# copy parent pom and module sources
COPY pom.xml ./
COPY common/topology-core/pom.xml common/topology-core/pom.xml
COPY common/docker-client/pom.xml common/docker-client/pom.xml
COPY common/swarm-model/pom.xml common/swarm-model/pom.xml
COPY common/control-plane-core/pom.xml common/control-plane-core/pom.xml
COPY common/control-plane-spring/pom.xml common/control-plane-spring/pom.xml
COPY common/worker-sdk/pom.xml common/worker-sdk/pom.xml
COPY test/pom.xml test/pom.xml
COPY e2e-tests/pom.xml e2e-tests/pom.xml
COPY trigger-service/pom.xml trigger-service/pom.xml
COPY moderator-service/pom.xml moderator-service/pom.xml
COPY processor-service/pom.xml processor-service/pom.xml
COPY observability/pom.xml observability/pom.xml
COPY postprocessor-service/pom.xml postprocessor-service/pom.xml
COPY log-aggregator-service/pom.xml log-aggregator-service/pom.xml
COPY generator-service/pom.xml generator-service/pom.xml
COPY orchestrator-service/pom.xml orchestrator-service/pom.xml
COPY swarm-controller-service/pom.xml swarm-controller-service/pom.xml
COPY scenario-manager-service/pom.xml scenario-manager-service/pom.xml
COPY common/topology-core/src common/topology-core/src
COPY common/docker-client/src common/docker-client/src
COPY common/swarm-model/src common/swarm-model/src
COPY common/control-plane-core/src common/control-plane-core/src
COPY common/control-plane-spring/src common/control-plane-spring/src
COPY common/worker-sdk/src common/worker-sdk/src
COPY test/src test/src
COPY e2e-tests/src e2e-tests/src
COPY trigger-service/src trigger-service/src
COPY moderator-service/src moderator-service/src
COPY processor-service/src processor-service/src
COPY observability/src observability/src
COPY postprocessor-service/src postprocessor-service/src
COPY log-aggregator-service/src log-aggregator-service/src
COPY generator-service/src generator-service/src
COPY orchestrator-service/src orchestrator-service/src
COPY swarm-controller-service/src swarm-controller-service/src
COPY scenario-manager-service/src scenario-manager-service/src
# build only this module (+ its dependencies) to get a fat jar with Boot repackage
RUN mvn -q -B -DskipTests -pl trigger-service -am package

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /app/trigger-service/target/*.jar app.jar
COPY trigger-service/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]
