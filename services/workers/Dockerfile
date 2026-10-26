# Pinned by digest (AOS-622); Dependabot proposes updates.
FROM golang:1.27-alpine@sha256:b8c9b82affbbb82a020bbcf998dad2ba9516944ac7e883524642a481a9505e9c AS builder

# Fall back to fetching from source (verified against sum.golang.org) if the module proxy is unreachable.
ARG GOPROXY=https://proxy.golang.org|direct
ENV GOPROXY=${GOPROXY}
RUN apk --no-cache add git

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o worker ./cmd/worker

FROM alpine:3.24@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a
RUN apk --no-cache add ca-certificates \
    && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=builder /app/worker .
USER app

ENTRYPOINT ["./worker"]
