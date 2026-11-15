package dev.chainguard.thwart.migrate;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Thymeleaf to Thwart lookup tables and every report message. Structural rewrites (scripts,
 * natural-template comments, fragment signatures, layouts) are in {@link Migrator},
 * {@link Expressions} and {@link Layouts}.
 */
final class ConversionRules {
  private ConversionRules() {}

  static final String THYMELEAF_PREFIX = "th:";
  static final String THWART_PREFIX = "t:";
  static final String THYMELEAF_XMLNS = "xmlns:th";
  static final String THWART_XMLNS = "xmlns:t";
  static final String THWART_NAMESPACE = "https://thwart.chainguard.dev";
  static final Set<String> THYMELEAF_NAMESPACES =
      Compat.setOf("http://www.thymeleaf.org", "https://www.thymeleaf.org");

  /** Header attribute that declares template parameters (inserted as a stub when missing). */
  static final String PARAMS = "t:params";

  /** Placeholder type for parameters whose type the tool cannot know. */
  static final String UNKNOWN_TYPE = "Object";

  /**
   * {@code th:} attributes that are left in place, unrenamed, and reported. Keys are lower-case
   * attribute names. {@code th:on*} and {@code th:style} are converted like any other attribute:
   * the compiler's error for a computed one names the fix.
   */
  private static final Map<String, String> UNSUPPORTED_ATTRIBUTES = Compat.mapOf(
      ConversionRules.THYMELEAF_INLINE, "`th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or put data in a `<script type=\"application/json\">` block",
      "th:styleappend", "`th:styleappend` is not supported; merge it into one `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend`",
      "th:include", "`th:include` is not supported; use `t:insert` or `t:replace` (check the host-element semantics)",
      "th:substituteby", "`th:substituteby` is not supported; use `t:replace`",
      "th:assert", "`th:assert` is not supported; check it in the controller (or guard the element with `t:if`)",
      "th:attrappend", "`th:attrappend` is not supported; use `t:classappend`, or one `t:<attr>` with a `|...|` literal",
      "th:attrprepend", "`th:attrprepend` is not supported; use `t:classappend`, or one `t:<attr>` with a `|...|` literal",
      "th:alt-title", "`th:alt-title` is not supported; write `t:alt` and `t:title`",
      "th:lang-xmllang", "`th:lang-xmllang` is not supported; write `t:lang`",
      "th:attr", "`th:attr` is not supported; write one `t:<name>` per attribute: `th:attr=\"title=${a}, href=${b}\"` becomes `t:title=\"a\" t:href=\"b\"`",
      "th:errorclass", "`th:errorclass` is not supported; write `t:classappend=\"fields.hasErrors('<path>') ? '<classes>'\"` with the element's `t:field` path, merged into any existing `t:classappend`");

  private static final String DATA_TH_MESSAGE =
      "`%s` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:%s`";
  private static final String DIALECT_MESSAGE =
      "`%s` belongs to the `%s:` dialect, which Thwart does not support; port it by hand";

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
  static final Set<String> LITERAL_VALUES = Compat.setOf("remove", "import", "params");

  /** Characters (besides whitespace) that force a rewritten unquoted value into double quotes. */
  static final String UNQUOTED_VALUE_FORBIDDEN = "\"'=<>`";

  /** Attributes whose value is a selection path on {@code t:object}, not a root variable. */
  static final Set<String> SELECTION_VALUES = Compat.setOf("field", "errors");

  /** The assignment attribute: comma-separated {@code name=expr} locals ({@code th:with="n=..."}). */
  static final String WITH = "with";

  static final String FRAGMENT = "fragment";
  static final String EACH = "each";
  static final String OBJECT = "object";

  /** Attributes whose value may be a bare {@code tpl :: name(args)} fragment reference. */
  static final Set<String> FRAGMENT_REFERENCES = Compat.setOf("insert", "replace");

  /** Thymeleaf's implicit loop status variable suffix ({@code owner} gets {@code ownerStat}). */
  static final String IMPLICIT_STATUS_SUFFIX = "Stat";

  /** Thymeleaf expression utility objects that become Thwart function namespaces. */
  static final Map<String, String> NAMESPACES = Compat.mapOf(
      "#strings", "strings",
      "#numbers", "numbers",
      "#temporals", "temporals",
      "#lists", "lists",
      "#fields", "fields",
      "#ids", "ids");

  /** Utility calls {@code #obj.fn(x)} that become a built-in method on the argument, {@code x.m()}. */
  static final Map<String, String> RECEIVER_CALLS = Compat.mapOf(
      "#lists.isEmpty", "isEmpty",
      "#lists.size", "size");

  /**
   * Utility calls whose Thwart spelling differs, replaced verbatim (outside string literals)
   * before {@link #NAMESPACES} applies.
   */
  static final Map<String, String> RENAMED_CALLS = Compat.mapOf(
      "#strings.listJoin(", "strings.join(",
      "#fields.hasAnyErrors()", "fields.hasErrors('*')",
      "#fields.allErrors()", "fields.errors('*')",
      "#fields.hasGlobalErrors()", "fields.hasErrors('global')",
      "#fields.globalErrors()", "fields.errors('global')");

  /**
   * Thymeleaf's double-brace selection of a field path, {@code *{{due}}}: inside {@code th:object}
   * the field's BindStatus display value (rejected or formatted), which is {@code fields.value('due')}.
   * Group 1 is the path: names and int indexes.
   */
  static final Pattern DOUBLE_BRACE_SELECTION =
      Pattern.compile("\\*\\{\\{\\s*([A-Za-z_]\\w*(?:\\[\\d+\\])*(?:\\.[A-Za-z_]\\w*(?:\\[\\d+\\])*)*)\\s*\\}\\}");

  /** Thymeleaf's {@code x['new']}: SpEL property access for {@code isNew()} ({@code new} is a keyword). */
  static final Pattern NEW_INDEX = Pattern.compile("\\[\\s*'new'\\s*\\]");
  static final String IS_NEW = ".isNew()";

  /**
   * Thymeleaf token runs that are not text literals (see {@code Expressions.quoteTokens}): numbers,
   * and, compared in lower case, its boolean, null and no-op tokens and word operators. Every other
   * token outside {@code ${...}} is literal text ({@code th:class="nav-item"}) and is quoted.
   */
  static final Pattern NUMBER = Pattern.compile("\\d+\\.?\\d*|\\.\\d+");
  /** Keywords in both TOKEN_KEYWORDS and KEYWORDS; declared first for static init order. */
  private static final List<String> WORD_OPERATORS =
      Arrays.asList("and", "or", "not", "eq", "ne", "neq", "lt", "gt", "le", "ge", "div", "mod");
  private static final List<String> LITERALS = Arrays.asList("true", "false", "null");
  /** Plus {@code _}: the no-op token is a keyword in a token run, not text. */
  static final Set<String> TOKEN_KEYWORDS = keywords("_");

  private static Set<String> keywords(String extra) {
    Set<String> set = new HashSet<>(WORD_OPERATORS);
    set.addAll(LITERALS);
    set.add(extra);
    return Collections.unmodifiableSet(set);
  }

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
      "Thymeleaf utility object `#%s` has no Thwart equivalent; use a `@TemplateFunctions` namespace or a view-type property";

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
   * the Thwart compiler rejects it with a specific error. Patterns run on the value with string
   * literal contents blanked, unless {@code inLiterals} (preprocessing applies inside literals).
   */
  static final List<Unsupported> UNSUPPORTED_EXPRESSIONS = Compat.listOf(
      new Unsupported(Pattern.compile("\\*\\{\\s*__"), true,
          "dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)"),
      new Unsupported(Pattern.compile("^(?!.*\\*\\{\\s*__).*__", Pattern.DOTALL), true,
          "`__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field"),
      new Unsupported(Pattern.compile("(?<![\\w.])T\\s*\\("), false,
          "`T(...)` static class access is not supported (never); compute the value in the controller, add it to the model and declare it in `t:params`, or call a static `@TemplateFunction`"),
      new Unsupported(Pattern.compile("(?<![\\w.])new\\s+[A-Za-z_]"), false,
          "object creation with `new` is not supported (never); compute the value in Java"),
      new Unsupported(Pattern.compile("(?<!\\w)@[A-Za-z_]"), false,
          "Spring bean references (`@bean`) are not supported (never); call the bean in the controller (or in a `@ModelAttribute` method of a `@ControllerAdvice` for a value every page needs), add the result to the model and declare it in `t:params`"),
      new Unsupported(Pattern.compile("\\.[?!^$]\\["), false,
          "collection selection/projection is not supported; compute the collection in Java"),
      new Unsupported(Pattern.compile("@\\{\\s*['$|(*]"), false,
          "link with a non-literal path; Thwart links take a literal path with placeholders, e.g. `@{/owners(page=p)}` or `@{/owners/{id}(id=x)}`"));

  /** How to move a value a template read from outside the model into it (guide section 1). */
  private static final String TO_MODEL = "declare it in `t:params` and read it as `%2$s` (guide section 1)";
  private static final String EVERY_PAGE = "a `@ModelAttribute(\"%2$s\")` method in a `@ControllerAdvice`";

  /**
   * Thymeleaf's ambient scopes, read as root variables: a Thwart template sees only its
   * {@code t:params}, so each read moves into the model. Keys are the root names; {@code %1$s} is the
   * read as a code span ({@code `session.user`}), {@code %2$s} the name to give the value in the
   * model. Not reported where the template declares the name itself ({@code t:params},
   * {@code th:with}, {@code th:each}, a fragment parameter).
   */
  static final Map<String, String> AMBIENT_ROOTS = Compat.mapOf(
      "session",
      "%1$s reads the HTTP session, which a Thwart template cannot see; in the controller add `%2$s` to the model"
          + " (`@SessionAttribute(name = \"%2$s\", required = false)` on the handler method, or " + EVERY_PAGE
          + " for a value every page needs), " + TO_MODEL,
      "param",
      "%1$s reads a request parameter, which a Thwart template cannot see; in the controller take it as"
          + " `@RequestParam(name = \"%2$s\", required = false) String %2$s`, add it to the model, " + TO_MODEL,
      "application",
      "%1$s reads an application (servlet context) attribute, which a Thwart template cannot see; add `%2$s` to"
          + " the model from " + EVERY_PAGE + ", " + TO_MODEL,
      // The model name is %2$s, as for every ambient read; the guide's recipe (4.7) calls it csrfToken.
      "_csrf",
      "%1$s reads Spring Security's CSRF token request attribute, which a Thwart template cannot see; the starter"
          + " already adds the `_csrf` hidden input to every same-origin POST form, so delete a hand-written one;"
          + " for JavaScript (htmx, `fetch`) add the token (`CsrfToken.getToken()`) to the model from " + EVERY_PAGE
          + ", " + TO_MODEL + " in a script declaration (`var %2$s = [[${%2$s}]];`, guide section 4.4), not a `<meta>`");

  /** The model name suggested for an ambient read without a property ({@code ${session}}). */
  static final String AMBIENT_VALUE = "value";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

  /**
   * Thymeleaf expression objects that read the request, session, servlet context or evaluation
   * context. Keys are names without {@code #}; {@code %s} is the read as a code span. They are left
   * in place and reported with this fix-it instead of {@link #UNKNOWN_UTILITY_MESSAGE}.
   */
  private static final String REQUEST_MESSAGE =
      "%s reads the servlet request, which a Thwart template cannot see; compute the value in the controller, add"
          + " it to the model and declare it in `t:params` (a value every page needs, such as the request URI, comes"
          + " from a `@ModelAttribute` method in a `@ControllerAdvice` returning `request.getRequestURI()`; guide"
          + " section 1)";
  private static final String RESPONSE_MESSAGE =
      "%s uses the servlet response, which a Thwart template cannot see; do it in the controller, and pass any"
          + " value the page prints through the model and `t:params` (guide section 1)";
  private static final String SESSION_MESSAGE =
      "%s reads the HTTP session, which a Thwart template cannot see; in the controller add the value to the"
          + " model (`@SessionAttribute` on the handler method, or a `@ModelAttribute` method in a"
          + " `@ControllerAdvice` for a value every page needs) and declare it in `t:params` (guide section 1)";
  private static final String SERVLET_CONTEXT_MESSAGE =
      "%s reads the servlet context, which a Thwart template cannot see; add the value to the model from a"
          + " `@ModelAttribute` method in a `@ControllerAdvice` and declare it in `t:params` (guide section 1)";
  private static final String CONTEXT_MESSAGE =
      "%s reads Thymeleaf's evaluation context, which Thwart does not have; a template sees only its"
          + " `t:params`: read the parameter by name, and add any value the controller does not provide to the"
          + " model and to `t:params` (guide section 1)";
  static final Map<String, String> AMBIENT_OBJECTS = Compat.mapOf(
      "request", REQUEST_MESSAGE,
      "httpServletRequest", REQUEST_MESSAGE,
      "response", RESPONSE_MESSAGE,
      "httpServletResponse", RESPONSE_MESSAGE,
      "session", SESSION_MESSAGE,
      "httpSession", SESSION_MESSAGE,
      "servletContext", SERVLET_CONTEXT_MESSAGE,
      "ctx", CONTEXT_MESSAGE,
      "vars", CONTEXT_MESSAGE,
      "root", CONTEXT_MESSAGE,
      "execInfo",
      "%s reads Thymeleaf's execution info (template name and mode, time), which Thwart does not have; write"
          + " the value as a literal, or add it to the model and declare it in `t:params` (guide section 1)");

  /**
   * The report message for an ambient root read such as {@code session.user} or
   * {@code param['q']}: its {@link #AMBIENT_ROOTS} entry, with the property as the model name.
   */
  static String ambientReadMessage(String read) {
    int end = 0;
    while (end < read.length() && (Character.isLetterOrDigit(read.charAt(end)) || read.charAt(end) == '_')) {
      end++;
    }
    String root = read.substring(0, end);
    String name = read.startsWith(".", end) ? read.substring(end + 1)
        : read.startsWith("['", end) ? read.substring(end + 2, read.length() - 2) : AMBIENT_VALUE;
    if (!IDENTIFIER.matcher(name).matches()) {
      name = AMBIENT_VALUE;
    }
    return String.format(AMBIENT_ROOTS.get(root), "`" + read + "`", name);
  }

  /** Names that are never variables: plus {@code this}, SpEL's current object. */
  static final Set<String> KEYWORDS = keywords("this");

  /**
   * {@code <script type>} values whose content is JavaScript or JSON, compared stripped and in lower
   * case; the compiler's script context uses the same list.
   */
  static final Set<String> SCRIPT_TYPES = Compat.setOf("", "text/javascript", "application/javascript",
      "text/ecmascript", "application/ecmascript", "module", "application/json", "application/ld+json");

  /** Thymeleaf's inlining switch; {@code th:inline="none"} leaves {@code [[...]]} in its body alone. */
  static final String THYMELEAF_INLINE = "th:inline";
  static final String INLINE_NONE = "none";
  /** {@code th:inline="javascript"}: Thymeleaf's natural-template comments apply. */
  static final String INLINE_JAVASCRIPT = "javascript";

  static final String SCRIPT = "script";
  static final String TYPE = "type";

  /** {@code %s} is the literal as a Markdown code span. */
  static final String SCRIPT_INLINE_UNQUOTED_MESSAGE =
      "%s in a script was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes; a string renders"
          + " the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a"
          + " quoted string: check `== \"true\"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a"
          + " string for a non-null value)";
  /** {@code %1$s} is the comment and default, {@code %2$s} the inline, {@code %3$s} the dropped default. */
  static final String SCRIPT_NATURAL_COMMENT_MESSAGE =
      "%1$s in a script was rewritten to %2$s: Thymeleaf's natural template wrote the value in place of the comment and dropped %3$s, and `[[...]]` inside a comment is a build error in Thwart";
  /** Appended to {@link #SCRIPT_NATURAL_COMMENT_MESSAGE} for {@code [(...)]}. */
  static final String SCRIPT_NATURAL_UNESCAPED_MESSAGE =
      "; `[(...)]` is a build error in a Thwart script: write `[[...]]`, which outputs a JSON value, or pass code another way";
  /** {@code %1$s} is the comment, {@code %2$s} the text after it that Thymeleaf drops. */
  static final String SCRIPT_NATURAL_COMMENT_MANUAL_MESSAGE =
      "%1$s in a script is a Thymeleaf natural-template comment, but it is not one inline followed by one literal (Thymeleaf drops %2$s after it, up to the next `;`, `,`, `)`, `}`, `]`, `//` or line end), so it was not rewritten; `[[...]]` inside a comment is a build error in Thwart: replace the comment and the text Thymeleaf drops with the inline, as in `var a = /*[[${x}]]*/ 'd';` to `var a = [[${x}]];`";

  /** {@code %1$s} is the link as a Markdown code span, {@code %2$s} its path variables and values. */
  static final String NULL_PATH_VARIABLE_MESSAGE =
      "link %1$s fails the render if a path variable is `null` (%2$s), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide section 3, step 4)";

  /**
   * Thymeleaf attributes that write an empty value for {@code null} (its non-removable attribute
   * processors), where Thwart omits the attribute.
   */
  static final Set<String> EMPTY_FOR_NULL = Compat.setOf("value", "href", "src", "action", "name", "type");
  /** {@code %1$s} is the attribute, {@code %2$s} its converted value as a code span, {@code %3$s} the HTML name. */
  static final String NULL_ATTRIBUTE_MESSAGE =
      "`%1$s` = %2$s: for a `null` value Thymeleaf wrote `%3$s=\"\"`, but Thwart omits the attribute; if it can be `null` and the empty attribute matters, write `x ?: ''` for a `String` or `x == null ? '' : '' + x` (guide section 4.3)";

  /** {@code %s} is the selection as a code span. */
  static final String COMPOUND_SELECTION_MESSAGE =
      "%s is not converted: in Thwart `*{...}` holds one property path; write each path as its own selection, as in `*{firstName} + ' ' + *{lastName}` (guide section 4.3)";

  static final String PARAM_STUB_MESSAGE =
      "`%s` is declared as `Object` in the generated `t:params` stub; replace it with its view type";
  static final String FRAGMENT_PARAMS_MESSAGE =
      "fragment `%s` parameters declared as `Object` (%s); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)";
  static final String FRAGMENT_SIGNATURE_MESSAGE =
      "fragment signature `%s` was not understood; write it as `name(Type a, Type b)`";

  /** {@link Layouts}: page skeleton elements nested by a layout call. */
  private static final String PARSE_ERROR_REJECTED = "a parse error, which the build rejects";
  static final String LAYOUT_REMOVE_TAG_MESSAGE =
      "rewritten: added `t:remove=\"tag\"` to the `<%s>` this page passes to `%s`, which inserts it inside its own `<%s>`, so only its content is passed (a second one is "
          + PARSE_ERROR_REJECTED + "; guide section 4.8, Layouts)";
  static final String LAYOUT_ATTRIBUTES_MESSAGE =
      "`%s` is passed to `%s`, which inserts it inside its own `<%s>`: a second `<%s>` is " + PARSE_ERROR_REJECTED
          + ". Not rewritten, because `t:remove=\"tag\"` would drop the attributes a browser merges into the layout's `<%s>`. Fix: %s, then mark this `<%s>` `t:remove=\"tag\"` (guide section 4.8, Layouts)";
  /** Where to move attributes a removed tag would drop; {@link Layouts}. */
  static final String LAYOUT_PARAMETER_FIX =
      "give `%s` a `String` parameter for %s and set it on its `<%s>` (for example `String %s` and `<%s t:%s=\"%s\">`), and pass %s from this page and `null` from the layout's other calls";
  static final String ATTRIBUTE_SPACE_MESSAGE =
      "no space before attribute `%s` (" + PARSE_ERROR_REJECTED + "): a space was added, which a browser reads the same";
}
