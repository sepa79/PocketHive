FROM nginx:1.27-alpine

# Nginx config
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf

# Static UI
COPY ui/ /usr/share/nginx/html/

# Top-level metadata for UI
COPY CHANGELOG.md VERSION /usr/share/nginx/html/

