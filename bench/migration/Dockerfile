# Image for the migration benchmark. bench.py builds it with a `git archive` of this repo as the
# build context. The final image has the installed Tessera jars but none of this repo's sources,
# docs or history: the migration agent sees only what bench.py mounts.

FROM cgr.dev/chainguard/wolfi-base AS tessera
RUN apk add --no-cache openjdk-21-default-jdk maven-3.9 bash
ENV JAVA_HOME=/usr/lib/jvm/default-jvm
COPY . /src
WORKDIR /src
RUN mvn -B -ntp -q install -DskipTests -Dmaven.repo.local=/m2 \
      -pl tessera-runtime,tessera-compiler,tessera-spring-boot-starter,tessera-spring-boot-starter-javax,tessera-migrate -am

FROM cgr.dev/chainguard/wolfi-base
ARG CLAUDE_CODE_VERSION=2.1.308
RUN apk add --no-cache openjdk-21-default-jdk openjdk-8 maven-3.9 bash git curl python-3.13 nodejs-22 npm \
      coreutils findutils grep sed diffutils procps \
 && npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} \
 && node /usr/local/lib/node_modules/@anthropic-ai/claude-code/install.cjs \
 && adduser -D -h /home/bench bench \
 && mkdir -p /home/bench/.m2/repository && chown -R bench:bench /home/bench/.m2
# Tessera 0.1.0-SNAPSHOT plus its build's dependencies; bench.py copies this into the shared
# Maven repository volume before each batch.
COPY --from=tessera /m2 /opt/m2-seed
# The same tessera-migrate jar at a fixed path, for check.sh's record and compare.
COPY --from=tessera /m2/dev/tessera/tessera-migrate/0.1.0-SNAPSHOT/tessera-migrate-0.1.0-SNAPSHOT.jar \
     /opt/tessera-migrate.jar
# JDK 21 is the default (Tessera's build needs it); JDK 8 is for apps whose app.json says "java": 8.
ENV JAVA_HOME=/usr/lib/jvm/default-jvm \
    LANG=C.UTF-8 \
    MAVEN_ARGS="-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"
USER bench
WORKDIR /work
