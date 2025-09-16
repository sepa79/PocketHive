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
COPY CHANGELOG.md VERSION README.md /usr/share/nginx/html/
COPY docs/ /usr/share/nginx/html/docs/
