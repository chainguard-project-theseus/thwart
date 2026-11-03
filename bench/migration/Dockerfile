# Image for the migration benchmark. bench.py builds it with a `git archive` of this repo as the
# build context. The final image has the installed Tessera jars and the Claude Code plugin from
# plugins/tessera, but none of this repo's other sources, docs or history: beyond those, the
# migration agent sees only what bench.py mounts.

FROM cgr.dev/chainguard/wolfi-base AS tessera
# extra-ca.crt: bench.py writes BENCH_EXTRA_CA_CERTS here (empty unless set), for a TLS-intercepting proxy.
COPY extra-ca.crt /tmp/extra-ca.crt
RUN cat /tmp/extra-ca.crt >> /etc/ssl/certs/ca-certificates.crt
RUN apk add --no-cache openjdk-21-default-jdk maven-3.9 bash \
 && if [ -s /tmp/extra-ca.crt ]; then keytool -importcert -noprompt -cacerts -storepass changeit -alias extra -file /tmp/extra-ca.crt; fi
ENV JAVA_HOME=/usr/lib/jvm/default-jvm
COPY . /src
WORKDIR /src
# Every artifact the guide tells an app to use, including tessera-maven-plugin (#425).
# -Dinvoker.skip: the image needs only the installed artifacts, not the plugin's
# invoker IT (which -DskipTests does not skip and which fetches over the network).
RUN mvn -B -ntp -q install -DskipTests -Dinvoker.skip -Dmaven.repo.local=/m2 \
      -pl tessera-runtime,tessera-compiler,tessera-spring-boot-starter,tessera-spring-boot-starter-javax,tessera-maven-plugin,tessera-migrate -am

FROM cgr.dev/chainguard/wolfi-base
ARG CLAUDE_CODE_VERSION=2.1.308
COPY extra-ca.crt /tmp/extra-ca.crt
RUN cat /tmp/extra-ca.crt >> /etc/ssl/certs/ca-certificates.crt
RUN apk add --no-cache openjdk-21-default-jdk openjdk-8 maven-3.9 bash git curl python-3.13 nodejs-22 npm \
      coreutils findutils grep sed diffutils procps postgresql-18 postgresql-18-client libfaketime \
      chromium py3.13-websocket-client \
 && if [ -s /tmp/extra-ca.crt ]; then keytool -importcert -noprompt -cacerts -storepass changeit -alias extra -file /tmp/extra-ca.crt; fi \
 && npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} \
 && node /usr/local/lib/node_modules/@anthropic-ai/claude-code/install.cjs \
 && adduser -D -h /home/bench bench \
 && mkdir -p /home/bench/.m2/repository && chown -R bench:bench /home/bench/.m2
# Tessera 0.1.0-SNAPSHOT plus its build's dependencies; bench.py copies this into the shared
# Maven repository volume before each batch.
COPY --from=tessera /m2 /opt/m2-seed
# The Claude Code plugin we ship (the migration skill and its guide), from the same commit as the
# jars; lib/run-agent.sh loads it with --plugin-dir.
COPY plugins/tessera /opt/tessera-plugin
# The same tessera-migrate jar at a fixed path, for check.sh's record and compare.
COPY --from=tessera /m2/dev/tessera/tessera-migrate/0.1.0-SNAPSHOT/tessera-migrate-0.1.0-SNAPSHOT.jar \
     /opt/tessera-migrate.jar
# PostgreSQL is for apps that only run on it (budgetmaster): the app's start command creates a
# throwaway cluster under /tmp. libfaketime pins the clock of an app whose pages show "today".
# JDK 21 is the default; JDK 8 is for apps whose app.json says "java": 8.
# Chromium and websocket-client are for lib/browser.py, the browser smoke check.
ENV JAVA_HOME=/usr/lib/jvm/default-jvm \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt \
    LANG=C.UTF-8 \
    MAVEN_ARGS="-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"
COPY bench/migration/lib/run-agent.sh /usr/local/bin/run-agent
USER bench
WORKDIR /work
