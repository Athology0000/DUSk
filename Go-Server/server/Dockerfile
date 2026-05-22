# Builds the Phantom Go auth/content server as a self-contained API image.
# The admin and license panels deploy separately (Vercel); the server serves
# no static frontend, so nothing frontend-related is built or copied here.
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOFLAGS=-mod=mod go build -o phantom-server ./cmd/server

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=builder /app/phantom-server .
EXPOSE 8080 8081
CMD ["./phantom-server"]
