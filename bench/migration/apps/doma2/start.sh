#!/bin/bash
# The admin app on in-memory H2 in MySQL mode instead of MySQL. The app ships only the MySQL
# driver, so H2 (the version Boot 3.4 manages) is copied from Maven and added with loader.path.
# Flyway runs migration/, the app's R__ scripts with two edits for H2: the PARTITION clause of
# send_mail_queue removed, and holiday dates written 2017-01-01 instead of 2017/1/1. Doma uses its
# H2 dialect (its MySQL dialect pages with SQL_CALC_FOUND_ROWS). Messages exist only in Japanese,
# so the locale is fixed to ja_JP. Mail (the password reset) goes to the SMTP sink. Clock at
# 2025-06-15 10:00 JST (libfaketime).
set -eu
mvn -B -ntp -q dependency:copy -Dartifact=com.h2database:h2:2.3.232 -DoutputDirectory=/tmp/h2
h2=/tmp/h2/h2-2.3.232.jar
python3 /bench/lib/smtp.py &
exec env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="@2025-06-15 10:00:00" FAKETIME_DONT_FAKE_MONOTONIC=1 TZ=Asia/Tokyo java -Duser.language=ja -Duser.country=JP -Dloader.path=$h2 -cp sample-web-admin/build/libs/sample-web-admin-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --server.port=8080 \
  '--spring.datasource.url=jdbc:h2:mem:sample;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,USER' \
  --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= \
  --spring.flyway.enabled=true --spring.flyway.locations=filesystem:/bench/app/migration --spring.flyway.baseline-on-migrate=true --spring.docker.compose.enabled=false \
  --application.fileUploadLocation=/tmp/upload --spring.profiles.active=bench --doma.dialect=h2 --spring.mail.host=localhost --spring.mail.port=2525 --spring.web.locale=ja_JP --spring.web.locale-resolver=fixed
