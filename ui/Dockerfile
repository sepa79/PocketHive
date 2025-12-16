# Build the React app
FROM node:20-alpine AS build

ARG VITE_STOMP_READONLY_USER=ph-observer
ARG VITE_STOMP_READONLY_PASSCODE=ph-observer
ENV VITE_STOMP_READONLY_USER=${VITE_STOMP_READONLY_USER}
ENV VITE_STOMP_READONLY_PASSCODE=${VITE_STOMP_READONLY_PASSCODE}

WORKDIR /app
# Install dependencies
COPY ui/package.json ui/package-lock.json ./
RUN npm ci
# Copy source
COPY ui/ .
# Build for production
RUN npm run build

# Serve with Nginx
FROM nginx:1.27-alpine
# Nginx configuration for static SPA and proxies
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
# Static site
COPY --from=build /app/dist/ /usr/share/nginx/html/
# Include project metadata and docs
COPY CHANGELOG.md README.md pom.xml /usr/share/nginx/html/
COPY docs/ /usr/share/nginx/html/docs/

# Generate /VERSION from pom.xml revision to avoid a separate source of truth.
RUN set -eu; \
  VERSION="$(sed -n 's/.*<revision>\\(.*\\)<\\/revision>.*/\\1/p' /usr/share/nginx/html/pom.xml | head -n 1 | tr -d '\\n')"; \
  if [ -z "$VERSION" ]; then echo "Failed to extract <revision> from pom.xml" >&2; exit 1; fi; \
  echo "$VERSION" > /usr/share/nginx/html/VERSION
