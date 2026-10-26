# AdminOS encrypted backups (AOS-621): pg_dump → age → R2. Postgres 18 client tools match the server major.
FROM alpine:3.24@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a

# age: modern file encryption (X25519 + ChaCha20-Poly1305), D-98. curl: S3 uploads signed with SigV4.
RUN apk add --no-cache "postgresql18-client~18" age bash coreutils curl jq tzdata \
    && addgroup -S -g 10001 backup && adduser -S -D -H -u 10001 -G backup backup \
    && mkdir -p /state /work && chown backup:backup /state /work

COPY --chmod=0555 bin/ /usr/local/lib/adminos-backup/
RUN ln -s /usr/local/lib/adminos-backup/backup.sh /usr/local/bin/backup \
    && ln -s /usr/local/lib/adminos-backup/restore.sh /usr/local/bin/restore

USER backup
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/backup"]
CMD ["schedule"]
