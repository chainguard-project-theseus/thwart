#!/bin/bash
# Shopizer 2.17.0 (sm-shop war, Spring Boot 2.3, JSP + Tiles) on JDK 8. The app creates its H2 file
# database in the working directory and loads the default store on first start, so start.sh runs
# it from a fresh directory under /tmp. Session ids stay out of URLs (cookie tracking only). Clock
# at 2025-06-15 10:00 UTC (libfaketime).
set -eu
rm -rf /tmp/shop && mkdir -p /tmp/shop && cd /tmp/shop
exec env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="@2025-06-15 10:00:00" FAKETIME_DONT_FAKE_MONOTONIC=1 \
  TZ=UTC java -jar /work/app/sm-shop/target/ROOT.war \
  --server.port=8080 --server.servlet.session.tracking-modes=cookie
