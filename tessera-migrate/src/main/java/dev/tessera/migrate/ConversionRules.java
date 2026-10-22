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
      Set.of("http://www.thymeleaf.org", "https://www.thymeleaf.org");

  /** Header attribute that declares template parameters (inserted as a stub when missing). */
  static final String PARAMS = "t:params";

  /** Placeholder type for parameters whose type the tool cannot know. */
  static final String UNKNOWN_TYPE = "Object";

  /**
   * {@code th:} attributes that are left in place, unrenamed, and reported. Keys are lower-case
   * attribute names; {@code th:on*} is handled by {@link #unsupportedAttribute}.
   */
  static final Map<String, String> UNSUPPORTED_ATTRIBUTES = Map.of(
      "th:inline", "`th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes",
      "th:style", "`th:style` is a build error by design (no dynamic CSS); use `t:classappend`",
      "th:styleappend", "`th:styleappend` is a build error by design (no dynamic CSS); use `t:classappend`",
      "th:include", "`th:include` is not supported; use `t:insert` or `t:replace` (check the host-element semantics)",
      "th:substituteby", "`th:substituteby` is not supported; use `t:replace`");

  static final String EVENT_HANDLER_MESSAGE =
      "`%s` is a build error by design (no dynamic event handlers); use a `data-*` attribute read by a script";
  static final String DATA_TH_MESSAGE =
      "`%s` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:%s`";
  static final String DIALECT_MESSAGE =
      "`%s` belongs to the `%s:` dialect, which Tessera does not support; port it by hand";

  /** Attribute prefixes that are plain XML/HTML, not template dialects. */
  static final Set<String> PLAIN_PREFIXES = Set.of("xmlns", "xml", "xlink", "th", "t");

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

  /** Attributes whose value is a keyword or literal, not an expression (renamed only). */
  static final Set<String> LITERAL_VALUES = Set.of("remove", "errorclass");

  /** Attributes whose value is a selection path on {@code t:object}, not a root variable. */
  static final Set<String> SELECTION_VALUES = Set.of("field", "errors");

  /** Attributes whose value is a comma-separated list of {@code name=expr} assignments. */
  static final Set<String> ASSIGNMENT_VALUES = Set.of("with", "attr");

  static final String FRAGMENT = "fragment";
  static final String EACH = "each";

  /** Attributes whose value may be a bare {@code tpl :: name(args)} fragment reference. */
  static final Set<String> FRAGMENT_REFERENCES = Set.of("insert", "replace");

  /** Thymeleaf's implicit loop status variable suffix ({@code owner} gets {@code ownerStat}). */
  static final String IMPLICIT_STATUS_SUFFIX = "Stat";

  /** Thymeleaf expression utility objects that become Tessera function namespaces. */
  static final Map<String, String> NAMESPACES = Map.of(
      "#strings", "strings",
      "#numbers", "numbers",
      "#temporals", "temporals",
      "#lists", "lists",
      "#fields", "fields");

  /** Utility calls {@code #obj.fn(x)} that become a built-in method on the argument, {@code x.m()}. */
  static final Map<String, String> RECEIVER_CALLS = Map.of(
      "#lists.isEmpty", "isEmpty",
      "#lists.size", "size");

  static final String UNKNOWN_UTILITY_MESSAGE =
      "Thymeleaf utility object `#%s` has no Tessera equivalent; use a `@TemplateFunctions` namespace or a view-type property";

  /** An expression construct that is left in place and reported. */
  record Unsupported(Pattern pattern, boolean inLiterals, String message) {}

  /**
   * Unconvertible expression constructs. When one matches, the whole value is left untouched so
   * the Tessera compiler rejects it with a specific error. Patterns run on the value with string
   * literal contents blanked, unless {@code inLiterals} (preprocessing applies inside literals).
   */
  static final List<Unsupported> UNSUPPORTED_EXPRESSIONS = List.of(
      new Unsupported(Pattern.compile("__"), true,
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
  static final Set<String> KEYWORDS = Set.of(
      "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "div", "mod",
      "true", "false", "null", "this");

  static final String PARAM_STUB_MESSAGE =
      "`%s` is declared as `Object` in the generated `t:params` stub; replace it with its view type";
  static final String FRAGMENT_PARAMS_MESSAGE =
      "fragment `%s` parameters declared as `Object` (%s); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)";
  static final String FRAGMENT_SIGNATURE_MESSAGE =
      "fragment signature `%s` was not understood; write it as `name(Type a, Type b)`";
}
