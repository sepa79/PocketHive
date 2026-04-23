#!/bin/bash
# PocketHive SQL-to-CSV Tool
# Wrapper script for Unix/Linux/macOS

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="$SCRIPT_DIR/target/sql-to-csv.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found at $JAR_FILE"
    echo "Please run: mvn clean package"
    exit 1
fi

java -jar "$JAR_FILE" "$@"
