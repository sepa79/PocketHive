# ---- Build (multi-module from repo root) ----
FROM maven:3.9.7-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml ./
COPY observability/pom.xml observability/pom.xml
COPY postprocessor-service/pom.xml postprocessor-service/pom.xml
COPY observability/src observability/src
COPY postprocessor-service/src postprocessor-service/src
RUN mvn -q -B -DskipTests -pl postprocessor-service -am package

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/postprocessor-service/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
