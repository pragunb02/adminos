# Pinned by digest (AOS-622); Dependabot proposes updates.
FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS builder
WORKDIR /app
# Inlined/compiled at build time (next.config.js): proxy target and the public OAuth client id.
ARG API_INTERNAL_URL=http://api-server:8080
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID=
ENV API_INTERNAL_URL=${API_INTERNAL_URL} NEXT_PUBLIC_GOOGLE_CLIENT_ID=${NEXT_PUBLIC_GOOGLE_CLIENT_ID}
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build && npm prune --omit=dev   # the runtime image gets production dependencies only

FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f
ENV NODE_ENV=production
# No package manager at run time (AOS-622): smaller surface, and npm's own bundled modules carry CVEs.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
WORKDIR /app
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
COPY --from=builder /app/next.config.mjs ./
USER node

EXPOSE 3000
# The login page needs no session; busybox wget is in the alpine base.
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/login || exit 1
# Inside the container only; compose publishes the port on 127.0.0.1. The local binary, not npx (no npm cache
# writes, nothing fetched at start, works on a read-only root filesystem).
CMD ["node_modules/.bin/next", "start", "-H", "0.0.0.0", "-p", "3000"]
