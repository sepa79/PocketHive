#!/bin/bash
set -e

echo "Building TCP Mock Server..."

# Build JAR
mvn clean package -DskipTests

# Build Docker image with no cache to ensure fresh copy
docker build --no-cache -t tcp-mock-server:latest .

echo "✓ TCP Mock Server image built successfully"

