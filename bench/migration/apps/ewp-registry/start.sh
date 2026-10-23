#!/bin/bash
# Starts the EWP Registry Service for the bench check, offline. The app accepts only https manifest
# URLs, so the fixture manifests in /bench/app/manifests are served by a local HTTPS server on
# port 8443 with the localhost certificate that ships in the app's local_configuration/keys, and
# the JVM trusts only that certificate. The manifest sources list is a fixture file read through a
# file:// URL, the catalogue's git working copy is a fresh repo under /tmp, the database is
# in-memory H2 (Flyway creates the schema), and mail goes to the bench's SMTP sink. The root URL
# is plain http, so the app serves its development design with the validators enabled and adds
# no self-manifest sources.
# Usage (from the app checkout): bash /bench/app/start.sh
set -eu
keys=$PWD/local_configuration/keys
python3 /bench/lib/smtp.py &
python3 - "$keys" <<'EOF' &
import functools, http.server, ssl, sys
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(sys.argv[1] + "/cert.pem", sys.argv[1] + "/private-key.pem")
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory="/bench/app/manifests")
server = http.server.ThreadingHTTPServer(("localhost", 8443), handler)
server.socket = ctx.wrap_socket(server.socket, server_side=True)
server.serve_forever()
EOF
rm -rf /tmp/ewp-repo /tmp/ewp-trust.p12 /tmp/ewp-app.log
git init -q /tmp/ewp-repo
keytool -importcert -noprompt -alias localhost -file "$keys/cert.pem" \
  -keystore /tmp/ewp-trust.p12 -storetype PKCS12 -storepass changeit > /dev/null

jar=$(ls target/ewp-registry-service-*.jar | head -1)
java -Djavax.net.ssl.trustStore=/tmp/ewp-trust.p12 -Djavax.net.ssl.trustStorePassword=changeit \
  -jar "$jar" \
  --spring.profiles.active=development \
  --server.port=8081 \
  --app.root-url=http://localhost:8080 \
  --app.manifest-sources.urls=file:///bench/app/manifest-sources.xml \
  --app.repo.path=/tmp/ewp-repo \
  --app.repo.enable-pushing=false \
  --app.admin-emails=admin@example.org \
  '--spring.datasource.url=jdbc:h2:mem:registry;DB_CLOSE_DELAY=-1' \
  --spring.mail.host=localhost --spring.mail.port=2525 > >(tee /tmp/ewp-app.log) 2>&1 &
app=$!

# The manifests are fetched in the background once the app answers, so its pages would depend on
# how far that got. Port 8080 opens only after every source has been fetched once (the updater
# logs "Reloading <url> (<status>)" for each), and forwards to the app on 8081.
sources=$(grep -c '<location>' /bench/app/manifest-sources.xml)
until [ "$(grep -c 'Reloading https://localhost:8443/' /tmp/ewp-app.log 2>/dev/null)" -ge "$sources" ]; do
  kill -0 $app 2>/dev/null || exit 1
  sleep 1
done
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
