#!/bin/bash
# Starts BudgetMaster for the bench check: a throwaway PostgreSQL cluster under /tmp (the app runs
# only on PostgreSQL or MariaDB), the settings file the app insists on, and the app itself on a
# pinned clock. BudgetMaster's pages show the month of "today" and the post handlers need its
# date cookie, so libfaketime starts the app's clock at a fixed instant (it then runs normally).
# Usage (from the app checkout): bash /bench/app/start.sh
set -eu
export PGDATA=/tmp/pgdata
initdb -A trust -U postgres -E UTF8 --locale=C.UTF-8 > /tmp/initdb.log
pg_ctl -w -l /tmp/postgres.log -o "-k /tmp -c listen_addresses=localhost -p 5432" start
psql -h localhost -U postgres -v ON_ERROR_STOP=1 -q \
  -c "CREATE USER budgetmaster PASSWORD 'budgetmaster'" \
  -c "CREATE DATABASE budgetmaster OWNER budgetmaster"

# The app reads ~/.Deadlocker/BudgetMaster/settings.properties and exits if it is missing. The
# template that ships with the app points at the database created above.
mkdir -p ~/.Deadlocker/BudgetMaster
cp BudgetMasterServer/src/main/resources/config/templates/settings.properties ~/.Deadlocker/BudgetMaster/

jar=$(ls BudgetMasterServer/build/*/BudgetMasterServer-v*.jar | head -1)
exec env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="@2025-06-15 10:00:00" \
  FAKETIME_DONT_FAKE_MONOTONIC=1 TZ=UTC \
  java -jar "$jar" --server.port=9000
