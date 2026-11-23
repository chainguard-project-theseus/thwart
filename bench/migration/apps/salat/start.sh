#!/bin/bash
# Salat on a throwaway MySQL (lower_case_table_names=1, as the dump expects): the repo's
# testdb/testdb.sql, then Liquibase migrates it to the current schema at startup. Profile "local"
# gives the dev login (?login-name=<sign>) and turns CSRF off.
# The clock stands still at 2019-09-16 09:00 (libfaketime), the week after the dump's bookings: pages
# show the time of day and when records were created.
# No TLS to MySQL: its certificate is dated after the pinned clock. Session ids stay out of URLs.
# Extra arguments go to the app (the coverage check adds a log level).
set -eu
bash /bench/lib/mysql.sh --lower-case-table-names=1
mysql --socket=/tmp/mysql.sock -uroot < testdb/testdb.sql
env LD_PRELOAD=/usr/lib/faketime/libfaketime.so.1 FAKETIME="2019-09-16 09:00:00" \
  FAKETIME_DONT_FAKE_MONOTONIC=1 TZ=Europe/Berlin \
  java -jar target/tb.jar --spring.profiles.active=local --server.port=8081 \
  --spring.datasource.url="jdbc:mysql://localhost:3306/salat?serverTimezone=Europe/Berlin&sslMode=DISABLED&allowPublicKeyRetrieval=true" \
  --server.servlet.session.tracking-modes=cookie \
  --salat.secret.active-key-id=test --salat.secret.keys.test=OHTqP+7nahpYHU5ywXXpaYn1EM7J5u4w2fzpXxmTmyw= "$@" &
app=$!
until curl -sf -o /dev/null http://localhost:8081/actuator/health; do
  kill -0 $app 2>/dev/null || exit 1
  sleep 1
done
# The dev login cookie is Secure, which a client on plain http never sends back, so every redirect
# would land logged out. Port 8080 forwards to the app and drops Secure from Set-Cookie.
exec python3 - <<'PY'
import http.client, http.server

class Proxy(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def forward(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        up = http.client.HTTPConnection("127.0.0.1", 8081)
        headers = {k: v for k, v in self.headers.items() if k.lower() != "connection"}
        up.request(self.command, self.path, body, headers)
        r = up.getresponse()
        data = r.read()
        self.send_response(r.status, r.reason)
        for k, v in r.getheaders():
            if k.lower() in ("transfer-encoding", "connection", "content-length"):
                continue
            if k.lower() == "set-cookie":
                v = "; ".join(p for p in v.split("; ") if p.lower() != "secure")
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        up.close()

    do_GET = do_POST = forward

    def log_message(self, *args):
        pass

http.server.ThreadingHTTPServer(("", 8080), Proxy).serve_forever()
PY
