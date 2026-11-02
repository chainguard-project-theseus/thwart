#!/bin/bash
# Starts the Posters demo store for the bench check: in-memory H2 with seed.sql (the demo
# customers with fixed ids), the Lucene index under /tmp, the app on port 8081.
# Usage (from the app checkout): bash /bench/app/start.sh
set -eu
java -jar implementation/target/posters-demo-store-*.jar \
  --server.port=8081 \
  '--spring.datasource.url=jdbc:h2:mem:posters;DB_CLOSE_DELAY=-1' \
  --spring.h2.console.enabled=false \
  --spring.jpa.defer-datasource-initialization=true \
  --spring.sql.init.mode=always \
  --spring.sql.init.data-locations=file:/bench/app/seed.sql \
  --posters.lucene-index-dir=/tmp/posters-lucene > /tmp/posters-app.log 2>&1 &
app=$!

# The app answers while its startup runners still import the catalog and build the search index,
# so its first pages would depend on how far that got. Port 8080 opens only after the last runner
# (the JFR event streamer) has started, and forwards to the app on 8081.
until grep -q 'Starting JFR Event Streaming' /tmp/posters-app.log 2>/dev/null; do
  kill -0 $app 2>/dev/null || { cat /tmp/posters-app.log; exit 1; }
  sleep 1
done
cat /tmp/posters-app.log
tail -f -n 0 /tmp/posters-app.log &
exec python3 - <<'EOF'
import socket, threading

def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    for s in (src, dst):
        try:
            s.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

server = socket.create_server(("", 8080), family=socket.AF_INET6, dualstack_ipv6=True)
while True:
    client, _ = server.accept()
    try:
        upstream = socket.create_connection(("127.0.0.1", 8081))
    except OSError:
        client.close()
        continue
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()
EOF
