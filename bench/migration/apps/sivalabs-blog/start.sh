#!/bin/bash
# Throwaway PostgreSQL under /tmp, then the blog with Flyway's sample data (V2: users, categories,
# tags) plus flyway/V100__bench_seed.sql, the SMTP sink and no Docker Compose.
# app.init-sample-data stays false: its DataInitializer picks random authors and cover images.
# Clock at 2025-06-15 10:00 UTC (libfaketime).
set -eu
export PGDATA=/tmp/pgdata
initdb -A trust -U postgres -E UTF8 --locale=C.UTF-8 > /tmp/initdb.log
pg_ctl -w -l /tmp/postgres.log -o "-k /tmp -c listen_addresses=localhost -p 5432" start
python3 /bench/lib/smtp.py &
mkdir -p /tmp/uploads
exec env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="@2025-06-15 10:00:00" FAKETIME_DONT_FAKE_MONOTONIC=1 \
  TZ=UTC java -jar target/sivalabs-blog-0.0.1-SNAPSHOT.jar \
  --spring.docker.compose.enabled=false --app.file-uploads-dir=/tmp/uploads \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/postgres \
  --spring.flyway.locations=classpath:db/migration,filesystem:/bench/app/flyway \
  --spring.mail.port=2525 --logging.level.com.sivalabs=INFO \
  --logging.level.sivalabs-blog.query-logger=WARN
