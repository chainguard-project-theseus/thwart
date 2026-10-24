# Proofs of the escapers

Tessera's compiler chooses an escaper for each output context. This page describes the proofs for the core methods in [`Escapers.java`](../tessera-runtime/src/main/java/dev/tessera/runtime/Escapers.java), their assumptions, and how to keep them passing when you edit an escaper.

[`verify.sh`](../tessera-runtime/src/jml/verify.sh) runs OpenJML 21.0.28 extended static checking with z3 5.1.0 and cvc5. It uses `--code-math=java`, so integer arithmetic wraps as it does in Java. The proofs cover every input that satisfies the methods' stated preconditions.

## What the proofs establish

| Method | Proven property |
| --- | --- |
| `escapeHtml(String)`, `escape(String, false)` | The returned string satisfies `htmlSafe`. |
| `escapeHtml(StringBuilder, String, false)` | The `StringOutput` fast path appends `htmlSafe` text and preserves the builder's earlier content. |
| `appendEscapedHtml(..., false)` | It appends `htmlSafe` text and preserves the builder's earlier content. |
| `appendEscapedHtmlChar(..., false)` | It appends one escaped character or surrogate pair, preserves earlier content, and returns the next input index. The appended segment satisfies the three parts of `htmlSafe`. |
| `appendTextBrace` | It appends a `{` and the empty comments needed at value or adjacent-brace boundaries, preserves earlier builder content, and constrains when the appended segment may end in `{`. |
| `needsHtmlEscape` | It returns true exactly when the character is not `htmlPlain`, or when `text` is true and the character is `{`. |
| `isAllowedUrl` | A true result means the URL has no scheme or has an allowed scheme as modeled below. |
| `schemeEquals` | A true result means the normalized scheme equals the expected text. |
| `writeJsonString` | It appends a quoted string whose body satisfies `jsonTokens`, preserves earlier builder content, and satisfies `jsonScriptSafe` across both quotes and the body. |
| `appendJsonChar` | It appends one JSON token, preserves earlier content, and returns the next input index. |
| `unicodeEscape` | It appends `\u` followed by four lowercase hexadecimal digits. |

### HTML attributes and other non-text contexts

These HTML postconditions apply when `text == false`. That path serves `attr`, `url`, `resourceUrl`, `refresh`, `jsonAttr`, and `rcdata`. `htmlSafe(t, from, to)` combines three properties:

- `htmlNoDelimiters`: The output contains no raw `<`, `>`, `"`, `'`, or NUL.
- `htmlEntitiesOnly`: Every `&` starts one of `&amp;`, `&lt;`, `&gt;`, `&quot;`, or `&#39;`.
- `htmlPairedSurrogates`: Every surrogate belongs to a high–low pair, making the output well-formed UTF-16.

Escaped output cannot open a tag or comment or close a quoted attribute value. Generated attributes use double quotes.

`escapeHtml(String)` allocates `new StringBuilder(n + 16)` when a character needs escaping. If `n > Integer.MAX_VALUE - 16`, that addition can overflow and cause `NegativeArraySizeException`. The specification permits this exception; the method produces no output in that case.

The HTML loop calls `appendEscapedHtmlChar` for each step. Its contract describes the segment appended by that step. After each call, the loop asserts `htmlEntitiesConcat` and `htmlPairsConcat` to establish that the complete output still satisfies the entity and surrogate properties. OpenJML proves `htmlPairsConcat` with cvc5 in a separate call. z3 misses the quantifier instance for the pair at the segment boundary `(m - 1, m)` and reports a spurious countermodel.

### Element text

In text mode (`text == true`), the specifications for `escape`, `escapeHtml(StringBuilder, String, true)`, `appendEscapedHtml`, and `appendEscapedHtmlChar` establish `htmlTextSafe` for the output. This mode serves `Escapers.text`, `escapeText`, and `htmlMessage` arguments.

`htmlTextSafe(t, from, to)` requires `htmlNoQuotes` (no raw `"`, `'`, or NUL), `htmlCommentsOnly` (every `<` and `>` belongs to a complete `BRACE_BREAK` comment, `<!---->`), `htmlEntitiesOnly`, `htmlPairedSurrogates`, and `htmlNoDoubleBrace` (no `{{`). A non-empty output also never starts or ends with `{`, so the escaper itself never writes a `{{`, and a non-empty value cannot complete one with a `{` written next to it. An empty value writes nothing, so static `{` + empty value + static `{` still renders `{{`: the compiler, not the escaper, rejects templates where a value can join static braces (including through an empty value).

`appendTextBrace` appends `{` with an empty comment before it when it starts the value, and after it when it ends the value or another `{` follows. The loop permits a trailing `{` only when the next input character is not `{`. It uses the z3-proved `htmlCommentsConcat` and `htmlNoDoubleBraceConcat` lemmas to preserve the properties across steps. Removing the trailing comment makes OpenJML report the postcondition that an output ending in `{` must have a following input character other than `{`; this negative check took 16 seconds.

### URL schemes

The proof models how a browser reads the scheme:

- `urlStart(s)` is the first index after leading C0 controls and spaces, defined here as characters `<= ' '`.
- `urlSchemeEnd(s)` is the first `:`, `/`, `?`, or `#` from that index, or the end of the string.
- `schemeText(s, from, to)` removes tabs, line feeds, and carriage returns from the range and lowercases ASCII letters.

If `isAllowedUrl(s)` returns true, either no `:` ends the scheme or `schemeText` is `http`, `https`, `mailto`, or `tel`. The proof establishes that no unsafe scheme is accepted. It does not establish that every safe URL is accepted; [`EscapersTest`](../tessera-runtime/src/test/java/dev/tessera/runtime/EscapersTest.java) covers behavior in that direction.

### JSON strings

`writeJsonString` writes an opening `"`, a body, and a closing `"`. `jsonScriptSafe` excludes `<`, `>`, `&`, `'`, backtick, `$`, U+2028, U+2029, C0 controls, and DEL from everything it writes. The result cannot close `</script>`, open `<!--`, end a template literal, or contain a JavaScript line terminator.

`jsonTokens(body)` states that the body is a chain of these tokens:

- A `jsonPlain` character: at least U+0020, excluding DEL, `"`, `\`, `/`, `<`, `>`, `&`, `'`, backtick, `$`, U+2028, U+2029, and surrogates.
- A UTF-16 surrogate pair.
- One of `\"`, `\\`, `\/`, `\n`, `\r`, `\t`, `\b`, or `\f`.
- `\uXXXX`, with four lowercase hexadecimal digits.

The token set is prefix-free. A JSON or JavaScript lexer therefore reads the body as those tokens: an escaped quote cannot end the string, and `/` appears only inside `\/`, preventing raw `*/` and `</`. The postcondition uses `(\exists \string m; jsonTokens(...))`; the loop constructs the token-start mask `m` as a ghost variable. The proof does not establish that decoding the output reproduces the input.

The JSON loop calls `appendJsonChar`, whose contract describes the one-, two-, or six-character token appended by that step. It then asserts the corresponding `jsonAppend1`, `jsonAppend2`, or `jsonAppend6` lemma. `jsonClose` handles the closing quote. These lemmas establish that each append preserves `jsonTokens` and `jsonScriptSafe`.

The string writer handles strings, characters, enum names, `ZoneId` and other `java.time` values, and map keys used by `json` and `jsonAttr`.

## Trusted assumptions and proof boundaries

OpenJML trusts its bundled specifications for `String`, `CharSequence`, and `AbstractStringBuilder`, including the relevant `append`, `charAt`, `length`, and `toString` behavior. It also trusts our [`java.lang` overlays](../tessera-runtime/src/jml/java/lang/) for surrogate ranges, `StringBuilder` constructors, `append(CharSequence, int, int)` appending the selected range, and `StringBuilder.toString`. The overlays replace OpenJML's bundled `Character.jml` and `StringBuilder.jml`. They declare `Character.digit`, `toUpperCase`, and `toLowerCase` pure without result specifications so OpenJML's other JDK specifications remain well-formed.

The proof also assumes the static invariant that `HEX` contains `0`–`9` and `a`–`f`; OpenJML does not verify static initializers. OpenJML also assumes the static invariant that `BRACE_BREAK` has length seven and contains `<`, `!`, four `-` characters, and `>` in that order; without the invariant, it does not know the constant's characters. `urlStart` and `urlSchemeEnd` have property-based definitions rather than bodies. An index satisfying each definition always exists, but OpenJML does not check their consistency. The specifications quantify over `int` indices, consistent with Java strings and builders being shorter than 2³¹ characters.

The thin entry points `text`, `attr`, `url`, `resourceUrl`, `refresh`, `json`, `jsonAttr`, and `htmlMessage` are outside the proof. So are `writeJson` dispatch and `escapeHtml(Output, String)`: the proof does not model `Output` or `toText`/`String.valueOf`, and OpenJML cannot see that `Output.write` leaves `HEX` unchanged. `verify.sh` excludes `escapeHtml(Output, String)` by its fully qualified signature. Numbers, booleans, and JSON structure such as commas and brackets are also outside this proof.

## Run the proofs

From the repository root, run:

```sh
bash tessera-runtime/src/jml/verify.sh
```

You need JDK 21 `javac` on `PATH`, `curl`, `unzip`, and `sha256sum`. The script downloads the Linux x86_64 OpenJML 21.0.28 release, checks the roughly 490 MB ZIP's SHA-256, and extracts it to `tessera-runtime/target/openjml-21.0.28` (under the checkout, because sandboxes may mount `/tmp` and `/var/tmp` noexec). Set `OPENJML_HOME` to use another installation. It compiles the runtime with `javac --release 8`, then checks `Escapers.java` alone. A failed proof gives a nonzero exit code.

Without a local JDK, run the script from the repository root in a `cg microvm`, here on the Kubernetes backend:

```sh
cg microvm --backend=k8s --k8s-image=gcr.io/nghia-chainguard-ng/microvm-agent/chainguard.dev/microvm/k8s/cmd/agent:latest --cpus 4 --memory 8Gi -p openjdk-21-default-jdk -p bash -p busybox -p coreutils -p curl -p unzip -p findutils -p libstdc++ -p glibc -- bash tessera-runtime/src/jml/verify.sh
```

For local QEMU, drop the two Kubernetes flags. On a four-CPU, 8 GiB Kubernetes microvm, `verify.sh` took 457 seconds wall time, plus the one-time OpenJML download: the z3 call took 408 seconds for 19 methods and the cvc5 call took 6 seconds. The longest proofs were `writeJsonString` (159 seconds), `isAllowedUrl` (34 seconds), `appendEscapedHtmlChar` (32 seconds), `appendEscapedHtml` (26 seconds) and `appendJsonChar` (27 seconds); each other proof took under 25 seconds.

The proofs are not part of CI yet: run `bash tessera-runtime/src/jml/verify.sh` (or the microvm command above) by hand after changing an escaper.

## When you edit an escaper

- Rerun the script. A failure identifies a specification clause and line, such as `LoopInvariant`. `UNKNOWN` or a possible timeout means the prover ran out of time; break the proof into smaller obligations before weakening a specification.
- Keep specifications in the JML comments beside the code. They compile away and add no runtime dependency.
- Quantify with `int k` unless you need to match a fact stated with `\bigint`, such as a prefix-preservation clause. Use `s.chars[j]` inside quantified specifications instead of `s.charAt(j)`.
- Define model functions with `ensures \result <==> ...` and no body; OpenJML does not unfold a model method body. Mark functions that inspect only their arguments `no_state`.
- State character ranges directly in postconditions called by other methods, such as `'0' <= c && c <= '9'`. Callers could not unfold a character-level model function there.
- Move a loop body into a helper with a contract for one step, then prove composition with small lemmas over `\string` values. The same HTML and JSON reasoning inline timed out.

Two negative checks confirm that the contracts constrain the code. Replacing the `<` case in `appendEscapedHtmlChar` with `sb.append(c)` makes OpenJML report `The prover cannot establish an assertion (Postcondition ...)` for `htmlNoDelimiters` in about 45 seconds. Replacing the `/` case in `appendJsonChar` with `sb.append('/')` makes it report the one-character case's `jsonPlain` postcondition in 26 seconds.
