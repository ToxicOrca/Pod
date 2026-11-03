# syntax=docker/dockerfile:1
FROM golang:1.24-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
# go-sqlite3 needs cgo; the binary links against glibc (provided by distroless/base).
RUN CGO_ENABLED=1 go build -trimpath -ldflags "-s -w -X main.Version=${VERSION}" -o /out/pod-server . \
 && mkdir -p /out/data

FROM gcr.io/distroless/base-debian12:nonroot
COPY --from=build /out/pod-server /usr/local/bin/pod-server
# /data owned by the nonroot user so a fresh named volume is writable.
COPY --from=build --chown=65532:65532 /out/data /data
USER nonroot:nonroot
EXPOSE 8080
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/usr/local/bin/pod-server", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/pod-server"]
