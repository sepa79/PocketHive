# ---- Build (multi-module from repo root) ----
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /app
# copy parent pom and module sources
COPY pom.xml ./
COPY hive-generator-service/pom.xml hive-generator-service/pom.xml
COPY hive-moderator-service/pom.xml hive-moderator-service/pom.xml
COPY hive-processor-service/pom.xml hive-processor-service/pom.xml
COPY observability/pom.xml observability/pom.xml
COPY hive-postprocessor-service/pom.xml hive-postprocessor-service/pom.xml
COPY log-aggregator-service/pom.xml log-aggregator-service/pom.xml
COPY hive-generator-service/src hive-generator-service/src
COPY hive-moderator-service/src hive-moderator-service/src
COPY hive-processor-service/src hive-processor-service/src
COPY observability/src observability/src
COPY hive-postprocessor-service/src hive-postprocessor-service/src
COPY log-aggregator-service/src log-aggregator-service/src
# build only this module (+ its dependencies) to get a fat jar with Boot repackage
RUN mvn -q -B -DskipTests -pl hive-processor-service -am package

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/hive-processor-service/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
