#!/bin/sh
# CI for the Gradle example (#427), run by .chainguard/ci/gradle.yaml. Builds a copy of it against
# the tessera artifacts in the local Maven repository (run `mvn install` first), then checks that
# a template-only or message-only edit reruns compileJava and that template errors fail the build.
set -eu
src=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -R "$src/." "$work"
cd "$work"
template=src/main/resources/templates/hello.html
messages=src/main/resources/messages.properties

run() { gradle --no-daemon --console=plain -q run 2>&1; }
expect() {
  [ "$1" = "$2" ] || { printf 'expected: %s\ngot:\n%s\n' "$2" "$1"; exit 1; }
}
expect_failure() {
  if out=$(run); then printf 'expected the build to fail with: %s\ngot:\n%s\n' "$1" "$out"; exit 1; fi
  case "$out" in *"$1"*) ;; *) printf 'expected: %s\ngot:\n%s\n' "$1" "$out"; exit 1 ;; esac
}
edit() { sed -i.bak "$1" "$2" && rm "$2.bak"; }

expect "$(run)" '<p><span>Hello</span> <b>Ann &lt;b&gt;</b></p>'
echo "ok: renders"

edit 's/<b /<em /; s/<\/b>/<\/em>/' "$template"
expect "$(run)" '<p><span>Hello</span> <em>Ann &lt;b&gt;</em></p>'
echo "ok: a template-only edit recompiles"

edit 's/greeting\.name/greeting.nmae/' "$template"
expect_failure "hello.html:1:"
expect_failure "'nmae' is not a property of com.example.Greeting"
echo "ok: a template error fails the build"
edit 's/greeting\.nmae/greeting.name/' "$template"

edit 's/^hello=/greeting=/' "$messages"
expect_failure "unknown message key 'hello'"
echo "ok: a message-only edit recompiles"
