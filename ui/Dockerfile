# syntax=docker/dockerfile:1

# Build stage: compile React app
FROM node:20-alpine AS build
WORKDIR /app
COPY ui/package*.json ./
RUN npm ci
COPY ui/ ./
RUN npm run build

# Runtime stage: serve static build with nginx
FROM nginx:1.27-alpine
# Nginx config (provides /healthz and proxies)
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
# Static build output
COPY --from=build /app/dist/ /usr/share/nginx/html/
# Top-level metadata for UI
COPY CHANGELOG.md VERSION README.md /usr/share/nginx/html/
COPY docs/ /usr/share/nginx/html/docs/
COPY rules/ /usr/share/nginx/html/rules/
