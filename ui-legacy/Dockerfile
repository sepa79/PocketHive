FROM nginx:1.27-alpine

# Nginx config
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf

# Static UI
COPY ui/ /usr/share/nginx/html/

# Top-level metadata for UI
COPY CHANGELOG.md VERSION README.md /usr/share/nginx/html/
COPY docs/ /usr/share/nginx/html/docs/
COPY rules/ /usr/share/nginx/html/rules/
COPY docs/ /usr/share/nginx/html/docs/
