FROM node:24-alpine AS builder
WORKDIR /app
# Inlined/compiled at build time (next.config.js): proxy target and the public OAuth client id.
ARG API_INTERNAL_URL=http://api-server:8080
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID=
ENV API_INTERNAL_URL=${API_INTERNAL_URL} NEXT_PUBLIC_GOOGLE_CLIENT_ID=${NEXT_PUBLIC_GOOGLE_CLIENT_ID}
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:24-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
COPY --from=builder /app/next.config.mjs ./
USER node

EXPOSE 3000
# Inside the container only; compose publishes the port on 127.0.0.1.
CMD ["npx", "next", "start", "-H", "0.0.0.0", "-p", "3000"]
