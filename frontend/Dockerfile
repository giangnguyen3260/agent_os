# syntax=docker/dockerfile:1
FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-fund
COPY . ./
RUN npm run lint \
    && npm run format:check \
    && npm run test:tooling \
    && npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine AS runtime
COPY --chown=101:101 docker/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /workspace/dist/agentos-frontend/browser /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
