# ---- Build (multi-module from repo root) ----
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /app
# copy parent pom and module sources
COPY pom.xml ./
COPY generator-service/pom.xml generator-service/pom.xml
COPY moderator-service/pom.xml moderator-service/pom.xml
COPY processor-service/pom.xml processor-service/pom.xml
COPY observability/pom.xml observability/pom.xml
COPY postprocessor-service/pom.xml postprocessor-service/pom.xml
COPY log-aggregator-service/pom.xml log-aggregator-service/pom.xml
COPY trigger-service/pom.xml trigger-service/pom.xml
COPY generator-service/src generator-service/src
COPY moderator-service/src moderator-service/src
COPY processor-service/src processor-service/src
COPY observability/src observability/src
COPY postprocessor-service/src postprocessor-service/src
COPY log-aggregator-service/src log-aggregator-service/src
COPY trigger-service/src trigger-service/src
# build only this module (+ its dependencies) to get a fat jar with Boot repackage
RUN mvn -q -B -DskipTests -pl processor-service -am package

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/processor-service/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
