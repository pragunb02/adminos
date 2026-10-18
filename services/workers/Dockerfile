# Pinned by digest (AOS-622); Dependabot proposes updates.
FROM golang:1.27-alpine@sha256:f92b6ef800e499660581efdabdf25d9d817a9d124eaf900924f0504e7e27e12d AS builder

# Fall back to fetching from source (verified against sum.golang.org) if the module proxy is unreachable.
ARG GOPROXY=https://proxy.golang.org|direct
ENV GOPROXY=${GOPROXY}
RUN apk --no-cache add git

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o worker ./cmd/worker

FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
RUN apk --no-cache add ca-certificates \
    && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=builder /app/worker .
USER app

ENTRYPOINT ["./worker"]
