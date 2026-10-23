#!/bin/bash
# Starts ESPD for the bench check, offline and on a pinned clock. The response wizard's turnover
# fields default to the last five calendar years and some selection criteria print the current
# year, so libfaketime starts the app's clock at a fixed instant (it then runs normally).
# TED (contract notice lookup) is disabled by an empty base URL (a system property: Boot 1.4
# rejects an empty --arg=), Piwik stays off, and the eCertis link (only a URL handed to the
# pages' JavaScript) points at a local placeholder. Error pages
# leave out the stack trace, which names the view classes of whatever renders them, and the
# session id travels only in its cookie (no ;jsessionid= in the first page's links).
# Usage (from the app checkout): bash /bench/app/start.sh
set -eu
# The executable war the build makes (a jar if a migration changes the packaging).
app=$(ls espd-web/target/espd-web.war espd-web/target/espd-web.jar 2>/dev/null | head -1)
exec env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="@2025-06-15 10:00:00" \
  FAKETIME_DONT_FAKE_MONOTONIC=1 TZ=UTC \
  java -Dted.api.base.url= -jar "$app" --server.port=8080 --piwik.enabled=false \
  '--ecertis.criterion.url=http://localhost:8080/ecertis/[uuid]/?countryFilter=[country]&lang=[lang]' \
  --server.error.include-stacktrace=never --server.session.tracking-modes=cookie
