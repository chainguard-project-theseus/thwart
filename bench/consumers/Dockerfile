# Image for the consumer benchmark: the migration bench image (Thwart from this repo's HEAD, JDK 8
# and 21, Maven, the apps' helpers) plus a load generator and taskset. run.sh builds it after
# `bench.py image`.
FROM thwart-migration-bench
USER root
# oha, pinned by SHA-256 per architecture: Wolfi packages no oha or wrk.
ARG OHA_VERSION=1.17.0
ARG OHA_SHA256_AMD64=85c6eb98066b8c8bd513b2dd96fafd8f9863249ea653606bae1ca2e3a75c43d2
ARG OHA_SHA256_ARM64=0b360c858f6277a5fca691d38adbd2dbc8c344d049ddf1a12fee8a762351ea24
RUN apk add --no-cache util-linux-misc \
 && case "$(uname -m)" in \
      x86_64) arch=amd64 sum=$OHA_SHA256_AMD64 ;; \
      aarch64) arch=arm64 sum=$OHA_SHA256_ARM64 ;; \
      *) echo "no oha for $(uname -m)" >&2; exit 1 ;; \
    esac \
 && curl -fsSL -o /usr/local/bin/oha "https://github.com/hatoo/oha/releases/download/v${OHA_VERSION}/oha-linux-${arch}" \
 && echo "$sum  /usr/local/bin/oha" | sha256sum -c - \
 && chmod 755 /usr/local/bin/oha
USER bench
WORKDIR /work
