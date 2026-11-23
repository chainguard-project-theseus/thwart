#!/bin/bash
# Throwaway PostgreSQL under /tmp, then balaka with its Flyway schema, the integration-test seed
# (chart of accounts, templates, transactions, invoices, payroll, ...) and seed/ (records the
# integration seed lacks, with fixed ids). MFA off; admin/admin.
#
# libfaketime: PostgreSQL's clock stands still at 2025-06-15 10:00 UTC, so the seed's NOW() and
# CURRENT_DATE are the same on every start; the app's clock starts there (pages show "today").
# The migrations call gen_random_uuid(); a sequence-backed one in schema bench, ahead of pg_catalog
# on the search path, gives every seeded row the same id on every start.
# The dev profile skips the guard against the seeded admin/admin; its DEBUG root logging is turned
# down to INFO. "$@" is for extra flags when debugging by hand.
set -eu
export TZ=UTC PGDATA=/tmp/pgdata
ft="env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME_DONT_FAKE_MONOTONIC=1 FAKETIME_DISABLE_SHM=1"
$ft FAKETIME="2025-06-15 10:00:00" initdb -A trust -U postgres -E UTF8 --locale=C.UTF-8 > /tmp/initdb.log
$ft FAKETIME="2025-06-15 10:00:00" pg_ctl -w -l /tmp/postgres.log -o "-k /tmp -c listen_addresses=localhost -p 5432" start
psql -h localhost -U postgres -q -c "CREATE USER accounting PASSWORD 'accounting'" -c "CREATE DATABASE accountingdb OWNER accounting"
psql -h localhost -U accounting -d accountingdb -q <<'SQL'
CREATE SCHEMA bench;
CREATE SEQUENCE bench.uuid;
CREATE FUNCTION bench.gen_random_uuid() RETURNS uuid LANGUAGE sql
  AS $$ SELECT lpad(to_hex(nextval('bench.uuid')), 32, '0')::uuid $$;
ALTER DATABASE accountingdb SET search_path = public, bench, pg_catalog;
SQL
mkdir -p /tmp/docs
exec $ft FAKETIME="@2025-06-15 10:00:00" java -jar target/accounting-finance-*.jar --server.port=8080 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/accountingdb --spring.datasource.username=accounting --spring.datasource.password=accounting \
  --spring.flyway.locations=classpath:db/migration,filesystem:src/test/resources/db/test/integration,filesystem:/bench/app/seed \
  --app.encryption.key=Kea3OzaaPR89QOebS1b427C8xxRoztOft8rlSEL3W7k= --app.setup.enabled=false --app.mfa.enabled=false \
  --app.storage.documents.path=/tmp/docs --spring.thymeleaf.cache=true --spring.profiles.active=dev --logging.level.root=INFO "$@"
