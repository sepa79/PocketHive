# Stage 1: build
FROM node:20-alpine AS builder
WORKDIR /app

# Install dependencies using UI package manifests
COPY package.json package-lock.json ./
RUN npm ci

# Copy UI source code
COPY . .
RUN npm run build

# Stage 2: nginx
FROM nginx:alpine

# Supply custom nginx config and build output
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
