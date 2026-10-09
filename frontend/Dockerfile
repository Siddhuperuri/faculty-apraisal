# syntax=docker/dockerfile:1
# Frontend image: Next.js 16 on Node 20 (the version CI uses). Three stages; the image that runs holds only the traced
# server (next.config.ts: output "standalone"), the static files and public/. No source, no dev dependencies, no
# build cache. The build stage needs the internet (npm, and Google Fonts, which `next build` downloads once and serves
# from the application); nothing here does at run time. Context: this folder (frontend/).
# Used by docker-compose.production.yml; see docs/docker-deployment.md.

FROM node:20-bookworm-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:20-bookworm-slim AS build
WORKDIR /app
# BACKEND_URL is read by next.config.ts when `next build` runs and is written into the server's routing table, so it is
# a build argument. In the Docker set-up Nginx sends /api/* to the backend itself; this is the fallback path, and it must
# name the backend as the Docker network knows it.
ARG BACKEND_URL=http://backend:8080
ENV BACKEND_URL=${BACKEND_URL} \
    NEXT_OUTPUT=standalone \
    NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:20-bookworm-slim AS runtime
ARG BACKEND_URL=http://backend:8080
# HOSTNAME must be 0.0.0.0: Docker sets it to the container's name, and the server would listen on that address only.
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    BACKEND_URL=${BACKEND_URL} \
    PORT=3000 \
    HOSTNAME=0.0.0.0
WORKDIR /app
# Owned by root and read-only to the application. Only the cache folder is writable by the (unprivileged) node account.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
RUN install -d -o node -g node -m 0750 /app/.next/cache
USER node
EXPOSE 3000
CMD ["node", "server.js"]
