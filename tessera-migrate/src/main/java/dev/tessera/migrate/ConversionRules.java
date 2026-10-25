package dev.tessera.migrate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every Thymeleaf to Tessera conversion rule, as data. To support another construct, add a row
 * here; {@link Migrator} and {@link Expressions} only apply these tables.
 */
final class ConversionRules {
  private ConversionRules() {}

  static final String THYMELEAF_PREFIX = "th:";
  static final String TESSERA_PREFIX = "t:";
  static final String THYMELEAF_XMLNS = "xmlns:th";
  static final String TESSERA_XMLNS = "xmlns:t";
  static final String TESSERA_NAMESPACE = "https://tessera.dev";
  static final Set<String> THYMELEAF_NAMESPACES =
      Compat.setOf("http://www.thymeleaf.org", "https://www.thymeleaf.org");

  /** Header attribute that declares template parameters (inserted as a stub when missing). */
  static final String PARAMS = "t:params";

  /** Placeholder type for parameters whose type the tool cannot know. */
  static final String UNKNOWN_TYPE = "Object";

  /**
   * {@code th:} attributes that are left in place, unrenamed, and reported. Keys are lower-case
   * attribute names; {@code th:on*} is handled by {@link #unsupportedAttribute}.
   */
  private static final Map<String, String> UNSUPPORTED_ATTRIBUTES = Compat.mapOf(
      ConversionRules.THYMELEAF_INLINE, "`th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes",
      "th:style", "`th:style` is not converted; rewrite it as `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend` or a `t:data-*` attribute",
      "th:styleappend", "`th:styleappend` is not supported; merge it into one `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend`",
      "th:include", "`th:include` is not supported; use `t:insert` or `t:replace` (check the host-element semantics)",
      "th:substituteby", "`th:substituteby` is not supported; use `t:replace`");

  private static final String EVENT_HANDLER_MESSAGE =
      "`%s` is a build error by design (no dynamic event handlers); use a `data-*` attribute read by a script";
  private static final String DATA_TH_MESSAGE =
      "`%s` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:%s`";
  private static final String DIALECT_MESSAGE =
      "`%s` belongs to the `%s:` dialect, which Tessera does not support; port it by hand";

  /** Attribute prefixes that are plain XML/HTML, not template dialects. */
  private static final Set<String> PLAIN_PREFIXES = Compat.setOf("xmlns", "xml", "xlink", "th", "t");

  /**
   * Returns the report message for an attribute that is left untouched (not renamed, value not
   * rewritten), or {@code null} if the attribute is converted normally.
   */
  static String unsupportedAttribute(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    String message = UNSUPPORTED_ATTRIBUTES.get(lower);
    if (message != null) {
      return message;
    }
    if (lower.startsWith("th:on")) {
      return String.format(EVENT_HANDLER_MESSAGE, name);
    }
    if (lower.startsWith("data-th-")) {
      return String.format(DATA_TH_MESSAGE, name, name.substring("data-th-".length()));
    }
    int colon = lower.indexOf(':');
    if (colon > 0 && !PLAIN_PREFIXES.contains(lower.substring(0, colon))) {
      return String.format(DIALECT_MESSAGE, name, name.substring(0, colon));
    }
    return null;
  }

  /**
   * Attributes whose value is a keyword, literal or declaration, not an expression (renamed only).
   * {@code import} names classes and {@code params} declares types and names; neither holds roots.
   */
  static final Set<String> LITERAL_VALUES = Compat.setOf("remove", "errorclass", "import", "params");

  /** Characters (besides whitespace) that force a rewritten unquoted value into double quotes. */
  static final String UNQUOTED_VALUE_FORBIDDEN = "\"'=<>`";

  /** Attributes whose value is a selection path on {@code t:object}, not a root variable. */
  static final Set<String> SELECTION_VALUES = Compat.setOf("field", "errors");

  /** The only assignment attribute that declares locals ({@code th:with="n=..."}). */
  static final String WITH = "with";

  /** Attributes whose value is a comma-separated list of {@code name=expr} assignments. */
  static final Set<String> ASSIGNMENT_VALUES = Compat.setOf(WITH, "attr");

  static final String FRAGMENT = "fragment";
  static final String EACH = "each";

  /** Attributes whose value may be a bare {@code tpl :: name(args)} fragment reference. */
  static final Set<String> FRAGMENT_REFERENCES = Compat.setOf("insert", "replace");

  /** Thymeleaf's implicit loop status variable suffix ({@code owner} gets {@code ownerStat}). */
  static final String IMPLICIT_STATUS_SUFFIX = "Stat";

  /** Thymeleaf expression utility objects that become Tessera function namespaces. */
  static final Map<String, String> NAMESPACES = Compat.mapOf(
      "#strings", "strings",
      "#numbers", "numbers",
      "#temporals", "temporals",
      "#lists", "lists",
      "#fields", "fields");

  /** Utility calls {@code #obj.fn(x)} that become a built-in method on the argument, {@code x.m()}. */
  static final Map<String, String> RECEIVER_CALLS = Compat.mapOf(
      "#lists.isEmpty", "isEmpty",
      "#lists.size", "size");

  /**
   * Utility calls whose Tessera spelling differs, replaced verbatim (outside string literals)
   * before {@link #NAMESPACES} applies.
   */
  static final Map<String, String> RENAMED_CALLS = Compat.mapOf(
      "#strings.listJoin(", "strings.join(",
      "#fields.hasAnyErrors()", "fields.hasErrors('*')",
      "#fields.allErrors()", "fields.errors('*')");

  /** Thymeleaf's {@code x['new']}: SpEL property access for {@code isNew()} ({@code new} is a keyword). */
  static final Pattern NEW_INDEX = Pattern.compile("\\[\\s*'new'\\s*\\]");
  static final String IS_NEW = ".isNew()";

  /**
   * Thymeleaf token runs that are not text literals (see {@code Expressions.quoteTokens}): numbers,
   * and, compared in lower case, its boolean, null and no-op tokens and word operators. Every other
   * token outside {@code ${...}} is literal text ({@code th:class="nav-item"}) and is quoted.
   */
  static final Pattern NUMBER = Pattern.compile("\\d+\\.?\\d*|\\.\\d+");
  static final Set<String> TOKEN_KEYWORDS = Compat.setOf("true", "false", "null", "_",
      "and", "or", "not", "eq", "ne", "neq", "gt", "lt", "ge", "le", "div", "mod");

  /**
   * String-built links with a literal path and one query parameter, {@code @{'/p?k=' + ${x}}} and
   * {@code @{'/p?k=__${x}__'}}; both become {@code @{/p(k=${x})}}. Groups: path, name, expression.
   * Every pattern must start with a literal {@code @}: {@link Expressions#rewriteLinks} skips a
   * match whose first character is inside a string literal.
   */
  private static final String LINK_PATH = "@\\{\\s*'(/(?:[^'?{}()\\s_]|_(?!_))*)\\?(\\w+)=";
  static final List<Pattern> STRING_LINKS = Compat.listOf(
      Pattern.compile(LINK_PATH + "'\\s*\\+\\s*\\$\\{([^{}',]+)\\}\\s*\\}"),
      Pattern.compile(LINK_PATH + "__\\$\\{([^{}',]+)\\}__'\\s*\\}"));

  static final String UNKNOWN_UTILITY_MESSAGE =
      "Thymeleaf utility object `#%s` has no Tessera equivalent; use a `@TemplateFunctions` namespace or a view-type property";

  /** An expression construct that is left in place and reported. */
  static final class Unsupported {
    private final Pattern pattern;
    private final boolean inLiterals;
    private final String message;

    Unsupported(Pattern pattern, boolean inLiterals, String message) {
      this.pattern = pattern;
      this.inLiterals = inLiterals;
      this.message = message;
    }

    Pattern pattern() {
      return pattern;
    }

    boolean inLiterals() {
      return inLiterals;
    }

    String message() {
      return message;
    }
  }

  /**
   * Unconvertible expression constructs. When one matches, the whole value is left untouched so
   * the Tessera compiler rejects it with a specific error. Patterns run on the value with string
   * literal contents blanked, unless {@code inLiterals} (preprocessing applies inside literals).
   */
  static final List<Unsupported> UNSUPPORTED_EXPRESSIONS = Compat.listOf(
      new Unsupported(Pattern.compile("\\*\\{\\s*__"), true,
          "dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)"),
      new Unsupported(Pattern.compile("^(?!.*\\*\\{\\s*__).*__", Pattern.DOTALL), true,
          "`__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field"),
      new Unsupported(Pattern.compile("(?<![\\w.])T\\s*\\("), false,
          "`T(...)` static class access is not supported (never); expose the value through a view type or a `@TemplateFunction`"),
      new Unsupported(Pattern.compile("(?<![\\w.])new\\s+[A-Za-z_]"), false,
          "object creation with `new` is not supported (never); compute the value in Java"),
      new Unsupported(Pattern.compile("(?<!\\w)@[A-Za-z_]"), false,
          "Spring bean references (`@bean`) are not supported (never); pass the value as a parameter"),
      new Unsupported(Pattern.compile("\\.[?!^$]\\["), false,
          "collection selection/projection is not supported; compute the collection in Java"),
      new Unsupported(Pattern.compile("@\\{\\s*['$|(*]"), false,
          "link with a non-literal path; Tessera links take a literal path with placeholders, e.g. `@{/owners(page=p)}` or `@{/owners/{id}(id=x)}`"));

  /** Word operators and literals that are never variables. */
  static final Set<String> KEYWORDS = Compat.setOf(
      "and", "or", "not", "eq", "ne", "neq", "lt", "gt", "le", "ge", "div", "mod",
      "true", "false", "null", "this");

  /**
   * {@code <script type>} values whose content is JavaScript or JSON, compared stripped and in lower
   * case; the compiler's script context uses the same list. A {@code t:type} is not one of them.
   */
  static final Set<String> SCRIPT_TYPES = Compat.setOf("", "text/javascript", "application/javascript",
      "text/ecmascript", "application/ecmascript", "module", "application/json", "application/ld+json");

  /** Thymeleaf's inlining switch; {@code th:inline="none"} leaves {@code [[...]]} in its body alone. */
  static final String THYMELEAF_INLINE = "th:inline";
  static final String INLINE_NONE = "none";

  static final String SCRIPT = "script";
  static final String TYPE = "type";

  /** {@code %s} is the literal as a Markdown code span. */
  static final String SCRIPT_INLINE_UNQUOTED_MESSAGE =
      "%s in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders"
          + " the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a"
          + " quoted string: check `== \"true\"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a"
          + " string for a non-null value)";
  /** {@code %s} is the template literal as a Markdown code span. */
  static final String SCRIPT_INLINE_TEMPLATE_LITERAL_MESSAGE =
      "%s in a script is a template literal, and `[[...]]` inside it is a build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a tagged template keep them and write ``tag`${[[...]]}` ``";
  static final String SCRIPT_INLINE_IN_STRING_MESSAGE =
      "`%s` inside a quoted JavaScript string is a build error in Tessera (it writes a JSON value with its own quotes); rewrite as `\"text \" + %s + \" text\"`";

  static final String PARAM_STUB_MESSAGE =
      "`%s` is declared as `Object` in the generated `t:params` stub; replace it with its view type";
  static final String FRAGMENT_PARAMS_MESSAGE =
      "fragment `%s` parameters declared as `Object` (%s); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)";
  /** Raw output that the converter keeps; reported unless it is a message or already sanitized. */
  static final String UTEXT = "utext";
  static final Pattern SAFE_UTEXT = Pattern.compile("\\s*#\\{.*|.*\\bsanitize\\w*\\s*\\(.*", Pattern.DOTALL);
  static final String UTEXT_MESSAGE =
      "`%s` prints `%s` without escaping; `t:utext` takes a `SafeHtml`. For stored HTML write `t:utext=\"html.sanitize(%s)\"` (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace; guide section 4.10, Rich text), never a recursive fragment over a tree parsed in Java; for markup built in Java return a `SafeHtml`; for plain text use `t:text`";

  /** {@link RichTextCheck}: at the fragment, and at each insert of it from outside its recursion. */
  static final String RICH_TEXT_FRAGMENT_MESSAGE =
      "fragment `%s` rebuilds rich text from a tree parsed in Java: it inserts itself under a `t:switch` on the tag name, so the data decides which element nests in which; that is a build error once the build-time context verifier is enforced. Keep the stored HTML as a `String` and print it with `t:utext=\"html.sanitize(text)\"` where the fragment is inserted (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace for other classes or images), then delete this fragment and the Java code that builds its nodes (guide section 4.10, Rich text)";
  static final String RICH_TEXT_CALL_SITE_MESSAGE =
      "inserts the recursive rich-text fragment `%s` (see `%s:%d`); replace the insert with `t:utext=\"html.sanitize(text)\"` over the HTML string its nodes were parsed from (or `html.sanitizeRich`, or an application `HtmlPolicy`)";

  static final String FRAGMENT_SIGNATURE_MESSAGE =
      "fragment signature `%s` was not understood; write it as `name(Type a, Type b)`";
}
