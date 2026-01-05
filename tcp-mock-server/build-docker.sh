#!/bin/bash
set -e

echo "Building TCP Mock Server..."

# Build JAR
mvn clean package -DskipTests

# Prepare for Docker
mkdir -p .local-jars
cp target/tcp-mock-server-*.jar .local-jars/tcp-mock-server.jar

# Build Docker image
docker build -t tcp-mock-server:latest .

echo "✓ TCP Mock Server image built successfully"
docker images | grep tcp-mock-server
