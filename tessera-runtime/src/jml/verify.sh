#!/usr/bin/env bash
# Proves the JML specs of the core escapers in Escapers.java with OpenJML (docs/proofs.md).
# Needs a JDK 21 javac on the PATH, curl and unzip. Exits non-zero if any proof fails.
set -euo pipefail

cd "$(dirname "$0")/../../.."

version=21.0.28
sha256=0a151820c4e680e32cb2e399a02615182c748fc24e308865b317783d4836df8a
# Under the checkout (target/ is ignored): sandboxes may mount /tmp and /var/tmp noexec.
home=${OPENJML_HOME:-$PWD/tessera-runtime/target/openjml-$version}
# escapeHtml(Output, String) has no spec: it only hands the text to StringOutput or Output.write,
# which OpenJML cannot see leave the private HEX array alone.
excluded='dev.tessera.runtime.Escapers.escapeHtml(dev.tessera.runtime.Output,java.lang.String)'
methods=escapeHtml,escape,appendEscapedHtml,appendEscapedHtmlChar,appendTextBrace,htmlEntitiesConcat,htmlCommentsConcat,htmlNoDoubleBraceConcat,needsHtmlEscape,isAllowedUrl,schemeEquals,writeJsonString,appendJsonChar,unicodeEscape,jsonAppend1,jsonAppend2,jsonAppend6,jsonClose

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$home/openjml" ]; then
  mkdir -p "$(dirname "$home")"
  zip=$home.zip
  curl -sSfL -o "$zip" \
    "https://github.com/OpenJML/OpenJML/releases/download/$version/openjml-ubuntu-24.04-$version.zip"
  echo "$sha256  $zip" | sha256sum -c -
  # Unpack beside the target and move it into place, so an interrupted run leaves no partial
  # install. Restore the exec bits in case unzip dropped them (OpenJML runs its bundled JDK and
  # SMT solvers).
  rm -rf "$home.partial"
  mkdir -p "$home.partial"
  unzip -q "$zip" -d "$home.partial" -x 'lsp/*' 'tutorial/*' '*.pdf' '*.vsix'
  rm "$zip"
  find "$home.partial" -type f \( -name 'openjml*' -o -name '*.sh' -o -path '*/jdk/bin/*' \
    -o -name jspawnhelper -o -path '*/Solvers-*/*' \) -exec chmod +x {} +
  mv "$home.partial" "$home"
fi

# Escapers.java is checked from source; the rest of the runtime comes from class files, so
# OpenJML does not type-check (or trip over) code outside the proof.
classes=$tmp/classes
mkdir -p "$classes"
javac --release 8 -nowarn -d "$classes" $(find tessera-runtime/src/main/java -name '*.java')

bash "$home/openjml" --esc --progress --timeout 600 --code-math=java --esc-bv=false \
  --specs-path tessera-runtime/src/jml -classpath "$classes" --method "$methods" \
  --exclude "$excluded" \
  tessera-runtime/src/main/java/dev/tessera/runtime/Escapers.java

# z3 finds a spurious countermodel for this lemma; cvc5 proves it.
bash "$home/openjml" --esc --progress --timeout 600 --code-math=java --esc-bv=false --prover=cvc5 \
  --specs-path tessera-runtime/src/jml -classpath "$classes" --method htmlPairsConcat \
  tessera-runtime/src/main/java/dev/tessera/runtime/Escapers.java
