#!/bin/bash

echo "Building TCP Mock Server..."

# Build from parent directory to use shared dependencies
cd ..
mvn clean package -pl tcp-mock-server -am -DskipTests
cd tcp-mock-server

# Build Docker image
docker build -t tcp-mock-server:latest .

echo "TCP Mock Server built successfully!"
echo "Run with: docker-compose -f docker-compose.tcp-mock.yml up -d"
echo "REST API: http://localhost:8090/api/status"
echo "TCP Server: tcp://localhost:8080"
