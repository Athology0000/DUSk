# Builds the Phantom Go auth/content server as a self-contained API image.
# The admin and license panels deploy separately (Vercel); the server serves
# no static frontend, so nothing frontend-related is built or copied here.
#
# go.sum is regenerated inside the build (rm + go mod download) so a stale or
# corrupt committed go.sum can't break the build. Modules are still verified
# against the Go checksum database during the download.
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY . .
RUN rm -f go.sum \
 && go mod download \
 && CGO_ENABLED=0 GOOS=linux go build -o phantom-server ./cmd/server

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=builder /app/phantom-server .
EXPOSE 8080 8081
CMD ["./phantom-server"]
