#!/bin/bash
# Starts luckyBlog for the bench check, from the app checkout after its build.
# - The app is built without spring-boot-maven-plugin (a thin jar plus a lib/ directory), so it runs
#   from target/classes and its Maven runtime classpath, whatever the build packages.
# - MySQL is replaced by H2 2.2 in MySQL mode (the last H2 line that runs on Java 8), which accepts
#   the mappers' MySQL dialect (LIMIT a,b, INSERT IGNORE, INSERT ... SET, UPDATE/DELETE ... LIMIT,
#   LAST_INSERT_ID()), loaded from schema.sql and seed.sql next to this script. Druid's "wall" SQL
#   filter does not know H2, so only its "stat" filter stays on.
# - The undertow access log would go to /data/blog/log/server_log: off. The app's log4j setup logs
#   DEBUG to the console and to logs/ in the checkout; log4j.properties here logs INFO to the console.
# - libfaketime starts the clock at 2025-06-15 10:00 UTC (it then runs normally): a post added
#   through the admin pages is dated "today", and the archive and tag pages show that date.
# Usage (from the app checkout): bash /bench/app/start.sh
set -eu
mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=/tmp/luckyblog.cp
# Outside the checkout: the app's pom points the dependency plugin's outputDirectory elsewhere.
(cd /tmp && mvn -B -ntp -q dependency:copy -Dartifact=com.h2database:h2:2.2.224 -DoutputDirectory=/tmp/h2)
exec env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="@2025-06-15 10:00:00" \
  FAKETIME_DONT_FAKE_MONOTONIC=1 TZ=UTC \
  java -Duser.timezone=UTC -Dlog4j.configuration=file:/bench/app/log4j.properties \
  -Dserver.port=8080 \
  -Dserver.undertow.accesslog.enabled=false \
  '-Dspring.datasource.url=jdbc:h2:mem:blog;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE' \
  -Dspring.datasource.driverClassName=org.h2.Driver \
  -Dspring.datasource.username=sa -Dspring.datasource.password= \
  -Dspring.datasource.filters=stat \
  -Dspring.datasource.initialize=true \
  -Dspring.datasource.schema=file:/bench/app/schema.sql \
  -Dspring.datasource.data=file:/bench/app/seed.sql \
  -cp "target/classes:$(cat /tmp/luckyblog.cp):/tmp/h2/h2-2.2.224.jar" com.msun.luckyBlog.Launcher
