# Build the React app
FROM node:20-alpine AS build

WORKDIR /workspace/ui-v2
COPY packages/swarm-lifecycle-contract/ /workspace/packages/swarm-lifecycle-contract/
COPY ui-v2/package.json ui-v2/package-lock.json ./
RUN npm ci
COPY ui-v2/ .
RUN npm run build

# Build Docusaurus docs as static files mounted under /docs.
FROM node:20-alpine AS docs-build
WORKDIR /workspace/docs-site
COPY docs-site/package.json docs-site/package-lock.json ./
RUN npm ci
COPY docs-site/ ./
COPY docs/ /workspace/docs/
COPY archive/legacy-ui/assets/ /workspace/ui/assets/
COPY archive/legacy-ui/assets/ /workspace/archive/legacy-ui/assets/
ENV DOCS_URL=http://localhost:8088
ENV DOCS_BASE_URL=/docs/
RUN npm run build

# Serve with Nginx
FROM nginx:1.27-alpine
COPY ui-v2/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/ui-v2/dist/ /usr/share/nginx/html/
COPY CHANGELOG.md README.md pom.xml /usr/share/nginx/html/
COPY --from=docs-build /workspace/docs-site/build/ /usr/share/nginx/html/docs/

RUN set -eu; \
  VERSION="$(grep '<revision>' /usr/share/nginx/html/pom.xml | head -n 1 | sed 's/.*<revision>\(.*\)<\/revision>.*/\1/' | tr -d ' \t\n')"; \
  if [ -z "$VERSION" ]; then echo "Failed to extract <revision> from pom.xml" >&2; exit 1; fi; \
  echo "$VERSION" > /usr/share/nginx/html/VERSION
