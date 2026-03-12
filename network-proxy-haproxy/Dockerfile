FROM haproxy:3.1-alpine

USER root

RUN apk add --no-cache bash inotify-tools

COPY docker-entrypoint.sh /usr/local/bin/network-proxy-haproxy-entrypoint.sh
RUN chmod +x /usr/local/bin/network-proxy-haproxy-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/network-proxy-haproxy-entrypoint.sh"]
