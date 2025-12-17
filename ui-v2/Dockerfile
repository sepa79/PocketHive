# Build the React app
FROM node:20-alpine AS build

WORKDIR /app
COPY ui-v2/package.json ./
RUN npm install
COPY ui-v2/ .
RUN npm run build

# Serve with Nginx
FROM nginx:1.27-alpine
COPY ui-v2/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
