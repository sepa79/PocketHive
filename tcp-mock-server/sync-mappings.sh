#!/bin/bash
# Sync all local mapping files to TCP mock server

TCP_MOCK_URL="${TCP_MOCK_URL:-http://localhost:8083}"
MAPPINGS_DIR="${MAPPINGS_DIR:-./mappings}"
USERNAME="${TCP_MOCK_USERNAME:-admin}"
PASSWORD="${TCP_MOCK_PASSWORD:-admin}"

if [ ! -d "$MAPPINGS_DIR" ]; then
    echo "Error: Mappings directory not found: $MAPPINGS_DIR"
    exit 1
fi

echo "Syncing mappings from $MAPPINGS_DIR to $TCP_MOCK_URL..."

success=0
failed=0

for file in "$MAPPINGS_DIR"/*.{json,yaml,yml}; do
    [ -e "$file" ] || continue

    filename=$(basename "$file")
    echo -n "Uploading $filename... "

    # Determine content type based on file extension
    if [[ "$filename" =~ \.(yaml|yml)$ ]]; then
        content_type="application/x-yaml"
    else
        content_type="application/json"
    fi

    response=$(curl -s -w "\n%{http_code}" -X POST "$TCP_MOCK_URL/api/mappings" \
        -u "$USERNAME:$PASSWORD" \
        -H "Content-Type: $content_type" \
        --data-binary @"$file")

    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
        echo "✓"
        ((success++))
    else
        echo "✗ (HTTP $http_code)"
        ((failed++))
    fi
done

echo ""
echo "Sync complete: $success succeeded, $failed failed"
