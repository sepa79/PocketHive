# Build React UI
FROM node:20-alpine AS build
WORKDIR /app
COPY ui/package.json ui/package-lock.json ./
RUN npm ci
COPY ui/ ./
RUN npm run build

# Serve with Nginx
FROM nginx:1.27-alpine
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
COPY ui/assets /usr/share/nginx/html/assets
COPY ui/docs.html ui/readme.html ui/changelog.html ui/bindings.html ui/config.json /usr/share/nginx/html/
COPY CHANGELOG.md VERSION README.md /usr/share/nginx/html/
COPY docs/ /usr/share/nginx/html/docs/
COPY rules/ /usr/share/nginx/html/rules/
