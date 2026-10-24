#!/usr/bin/env bash
# Proves the JML contracts of the compiler's HTML rule functions with OpenJML (docs/rule-contracts.md).
# Needs a JDK 21 javac on the PATH, curl and unzip. Exits non-zero if any proof fails.
# One OpenJML run per source file; <FILE>_METHODS lists the methods proved there.
# usage: verify.sh [Tokenizer|HtmlParser|TextInlining]  (default: all)
set -euo pipefail

cd "$(dirname "$0")/../.."

version=21.0.28
sha256=0a151820c4e680e32cb2e399a02615182c748fc24e308865b317783d4836df8a
home=${OPENJML_HOME:-/var/tmp/openjml-$version}

TOKENIZER=src/main/java/dev/tessera/compiler/html/MarkupTokenizer.java
TOKENIZER_METHODS=${TOKENIZER_METHODS:-nextStructure,kind,isElementName,startsWith,keyword,delimitedScan}
HTMLPARSER=src/main/java/dev/tessera/compiler/html/HtmlParser.java
HTMLPARSER_METHODS=${HTMLPARSER_METHODS:-earlyEnd,bogusComment,cdataHole,earlyCommentEnd,commentStep,commentText,commentEnd,commentEndsAtDashDashGt,commentEndsAtDashDashBangGt,browserEndTag,asciiMatchesIgnoreCase,asciiLower,endTagDelimiter}
TEXTINLINING=src/main/java/dev/tessera/compiler/codegen/attr/TextInlining.java
TEXTINLINING_METHODS=${TEXTINLINING_METHODS:-scriptMarkupStart,htmlMarkupStart}

if [ ! -x "$home/openjml" ]; then
  zip=$(mktemp -d)/openjml.zip
  curl -sSfL -o "$zip" \
    "https://github.com/OpenJML/OpenJML/releases/download/$version/openjml-ubuntu-24.04-$version.zip"
  echo "$sha256  $zip" | sha256sum -c -
  unzip -q "$zip" -d "$home" -x 'lsp/*' 'tutorial/*' '*.pdf' '*.vsix'
  rm "$zip"
fi

# The proved file is checked from source; the rest of the compiler and the runtime come from class
# files, so OpenJML does not type-check (or trip over) code outside the proof.
classes=$(mktemp -d)
javac --release 8 -nowarn -d "$classes" $(find ../tessera-runtime/src/main/java src/main/java -name '*.java')

prove() { # file methods
  "$home/openjml" --esc --progress --timeout 600 --code-math=java --esc-bv=false \
    --specs-path src/jml -classpath "$classes" --method "$2" "$1"
}

only=${1:-all}
status=0
[ "$only" = all ] || [ "$only" = Tokenizer ] && { prove "$TOKENIZER" "$TOKENIZER_METHODS" || status=1; }
[ "$only" = all ] || [ "$only" = HtmlParser ] && { prove "$HTMLPARSER" "$HTMLPARSER_METHODS" || status=1; }
[ "$only" = all ] || [ "$only" = TextInlining ] && { prove "$TEXTINLINING" "$TEXTINLINING_METHODS" || status=1; }
exit $status
