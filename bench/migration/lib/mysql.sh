#!/bin/bash
# Start a throwaway MySQL under /tmp and wait until it answers. Runs inside the bench container.
#
# usage: bash /bench/lib/mysql.sh [MYSQLD_OPTION ...]
#
# The server listens on localhost:3306 and /tmp/mysql.sock; root has no password. Options such as
# --lower-case-table-names=1 go to both the initialization and the server. A second call while
# the server runs does nothing, so a start and a test command can both call it.
set -eu
sock=/tmp/mysql.sock
ping() { mysqladmin --no-defaults --socket=$sock -uroot ping >/dev/null 2>&1; }
ping && exit 0
opts=(--no-defaults --datadir=/tmp/mysql "$@")
[ -d /tmp/mysql ] || mysqld "${opts[@]}" --initialize-insecure > /tmp/mysql-init.log 2>&1
mysqld "${opts[@]}" --socket=$sock --port=3306 --bind-address=127.0.0.1 --mysqlx=OFF \
  --pid-file=/tmp/mysql.pid > /tmp/mysql.log 2>&1 &
for _ in $(seq 1 60); do ping && exit 0; sleep 1; done
echo "mysql.sh: MySQL did not start; see /tmp/mysql.log" >&2
exit 1
