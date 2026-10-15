FROM golang:1.27-alpine AS builder

# Fall back to fetching from source (verified against sum.golang.org) if the module proxy is unreachable.
ARG GOPROXY=https://proxy.golang.org|direct
ENV GOPROXY=${GOPROXY}
RUN apk --no-cache add git

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o worker ./cmd/worker

FROM alpine:3.24
RUN apk --no-cache add ca-certificates \
    && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=builder /app/worker .
USER app

ENTRYPOINT ["./worker"]
