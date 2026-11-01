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
      ConversionRules.THYMELEAF_INLINE, "`th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or put data in a `<script type=\"application/json\">` block",
      "th:style", "`th:style` is not converted; rewrite it as `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend`, or a `<script type=\"application/json\">` block read by a script (guide section 4.4, Migration recipes)",
      "th:styleappend", "`th:styleappend` is not supported; merge it into one `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend`",
      "th:include", "`th:include` is not supported; use `t:insert` or `t:replace` (check the host-element semantics)",
      "th:substituteby", "`th:substituteby` is not supported; use `t:replace`");

  private static final String EVENT_HANDLER_MESSAGE =
      "`%s` is a build error by design (no dynamic event handlers); put the value in a"
          + " `<script type=\"application/json\">` block, or a number, a boolean or a same-origin path in a"
          + " `data-*` attribute, and read it from a script (guide section 4.4, Migration recipes)";
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
   * SVG presentation attributes, lower case, whose value is CSS that may hold a {@code url()}. On
   * an SVG element the Tessera compiler gives them the {@code style} rules: a {@code String} value
   * is a build error. The compiler's {@code Contexts} has the same list.
   */
  private static final Set<String> SVG_STYLE_ATTRIBUTES = Compat.setOf(
      "fill", "stroke", "clip-path", "mask", "filter", "marker-start", "marker-mid", "marker-end", "marker", "cursor");

  /** SVG animation elements, lower case, whose {@code fill} is timing ({@code freeze}), not CSS. */
  private static final Set<String> SVG_TIMED_ANIMATIONS =
      Compat.setOf("animate", "set", "animatemotion", "animatetransform", "animatecolor");

  static final String SVG = "svg";
  /** SVG elements whose content is HTML again (the compiler's HTML integration points). */
  static final Set<String> SVG_HTML_PARENTS = Compat.setOf("foreignobject", "desc", "title");

  /**
   * Whether {@code local} (an attribute name without prefix, lower case) on the SVG element
   * {@code element} (lower case) takes the {@code style} rules in Tessera.
   */
  static boolean svgStyleAttribute(String local, String element) {
    return SVG_STYLE_ATTRIBUTES.contains(local)
        && !(local.equals("fill") && SVG_TIMED_ANIMATIONS.contains(element));
  }

  /** {@code %1$s} is the attribute, {@code %2$s} its name without {@code th:}. */
  static final String SVG_STYLE_MESSAGE =
      "`%1$s` on an SVG element is not converted: Tessera reads `%2$s` as CSS, like `style`, so a `String` value is a build error; rewrite it as `t:%2$s` from literals, numbers and `css.color(...)`/`css.url(...)` (guide section 4.4)";
  /** {@code %1$s} is the {@code th:attr} attribute, {@code %2$s} the attribute it sets. */
  static final String SVG_STYLE_IN_ATTR_MESSAGE =
      "`%2$s` in `%1$s` on an SVG element is CSS in Tessera, like `style`, so a `String` value is a build error; write it from literals, numbers and `css.color(...)`/`css.url(...)` (guide section 4.4)";

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
      "#fields", "fields",
      "#ids", "ids");

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
      "#fields.allErrors()", "fields.errors('*')",
      "#fields.hasGlobalErrors()", "fields.hasErrors('global')",
      "#fields.globalErrors()", "fields.errors('global')");

  /**
   * Thymeleaf's double-brace selection of a field path, {@code *{{due}}}: inside {@code th:object}
   * the field's BindStatus display value (rejected or formatted), which is {@code fields.value('due')}
   * (#133). Group 1 is the path: names and int indexes.
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
          "`T(...)` static class access is not supported (never); compute the value in the controller, add it to the model and declare it in `t:params`, or call a static `@TemplateFunction`"),
      new Unsupported(Pattern.compile("(?<![\\w.])new\\s+[A-Za-z_]"), false,
          "object creation with `new` is not supported (never); compute the value in Java"),
      new Unsupported(Pattern.compile("(?<!\\w)@[A-Za-z_]"), false,
          "Spring bean references (`@bean`) are not supported (never); call the bean in the controller (or in a `@ModelAttribute` method of a `@ControllerAdvice` for a value every page needs), add the result to the model and declare it in `t:params`"),
      new Unsupported(Pattern.compile("\\.[?!^$]\\["), false,
          "collection selection/projection is not supported; compute the collection in Java"),
      new Unsupported(Pattern.compile("@\\{\\s*['$|(*]"), false,
          "link with a non-literal path; Tessera links take a literal path with placeholders, e.g. `@{/owners(page=p)}` or `@{/owners/{id}(id=x)}`"));

  /** How to move a value a template read from outside the model into it (guide section 1). */
  private static final String TO_MODEL = "declare it in `t:params` and read it as `%2$s` (guide section 1)";
  private static final String EVERY_PAGE = "a `@ModelAttribute(\"%2$s\")` method in a `@ControllerAdvice`";

  /**
   * Thymeleaf's ambient scopes, read as root variables: a Tessera template sees only its
   * {@code t:params}, so each read moves into the model. Keys are the root names; {@code %1$s} is the
   * read as a code span ({@code `session.user`}), {@code %2$s} the name to give the value in the
   * model. Not reported where the template declares the name itself ({@code t:params},
   * {@code th:with}, {@code th:each}, a fragment parameter).
   */
  static final Map<String, String> AMBIENT_ROOTS = Compat.mapOf(
      "session",
      "%1$s reads the HTTP session, which a Tessera template cannot see; in the controller add `%2$s` to the model"
          + " (`@SessionAttribute(name = \"%2$s\", required = false)` on the handler method, or " + EVERY_PAGE
          + " for a value every page needs), " + TO_MODEL,
      "param",
      "%1$s reads a request parameter, which a Tessera template cannot see; in the controller take it as"
          + " `@RequestParam(name = \"%2$s\", required = false) String %2$s`, add it to the model, " + TO_MODEL,
      "application",
      "%1$s reads an application (servlet context) attribute, which a Tessera template cannot see; add `%2$s` to"
          + " the model from " + EVERY_PAGE + ", " + TO_MODEL,
      "_csrf",
      "%1$s reads Spring Security's CSRF token request attribute, which a Tessera template cannot see; the starter"
          + " already adds the `_csrf` hidden input to every same-origin POST form, so delete a hand-written one;"
          + " anywhere else (a `<meta>` for JavaScript) add the token (`CsrfToken.getToken()`) to the model from "
          + EVERY_PAGE + ", " + TO_MODEL);

  /** The model name suggested for an ambient read without a property ({@code ${session}}). */
  static final String AMBIENT_VALUE = "value";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

  /**
   * Thymeleaf expression objects that read the request, session, servlet context or evaluation
   * context. Keys are names without {@code #}; {@code %s} is the read as a code span. They are left
   * in place and reported with this fix-it instead of {@link #UNKNOWN_UTILITY_MESSAGE}.
   */
  private static final String REQUEST_MESSAGE =
      "%s reads the servlet request, which a Tessera template cannot see; compute the value in the controller, add"
          + " it to the model and declare it in `t:params` (a value every page needs, such as the request URI, comes"
          + " from a `@ModelAttribute` method in a `@ControllerAdvice` returning `request.getRequestURI()`; guide"
          + " section 1)";
  private static final String RESPONSE_MESSAGE =
      "%s uses the servlet response, which a Tessera template cannot see; do it in the controller, and pass any"
          + " value the page prints through the model and `t:params` (guide section 1)";
  private static final String SESSION_MESSAGE =
      "%s reads the HTTP session, which a Tessera template cannot see; in the controller add the value to the"
          + " model (`@SessionAttribute` on the handler method, or a `@ModelAttribute` method in a"
          + " `@ControllerAdvice` for a value every page needs) and declare it in `t:params` (guide section 1)";
  private static final String SERVLET_CONTEXT_MESSAGE =
      "%s reads the servlet context, which a Tessera template cannot see; add the value to the model from a"
          + " `@ModelAttribute` method in a `@ControllerAdvice` and declare it in `t:params` (guide section 1)";
  private static final String CONTEXT_MESSAGE =
      "%s reads Thymeleaf's evaluation context, which Tessera does not have; a template sees only its"
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
      "%s reads Thymeleaf's execution info (template name and mode, time), which Tessera does not have; write"
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
  /** {@code th:inline="javascript"}: Thymeleaf's natural-template comments apply (#315). */
  static final String INLINE_JAVASCRIPT = "javascript";

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
      "%s in a script is a template literal, and `[[...]]` inside it is a build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a tagged template declare the value above the code (`var v = [[...]];`) and write ``tag`${v}` `` after it";
  /** {@code %s} is the inline (its own Markdown code span). */
  static final String SCRIPT_INLINE_IN_COMMENT_MESSAGE =
      "`%s` inside a JavaScript comment is a build error in Tessera (its value would be written inside the comment and the page would never use it); move it out of the comment: Thymeleaf's natural template `var a = /*[[${x}]]*/ 'd';` becomes `var a = [[${x}]];`";
  /** {@code %1$s} is the comment and default, {@code %2$s} the inline, {@code %3$s} the dropped default. */
  static final String SCRIPT_NATURAL_COMMENT_MESSAGE =
      "%1$s in a script was rewritten to %2$s: Thymeleaf's natural template wrote the value in place of the comment and dropped %3$s, and `[[...]]` inside a comment is a build error in Tessera";
  /** Appended to {@link #SCRIPT_NATURAL_COMMENT_MESSAGE} for {@code [(...)]}. */
  static final String SCRIPT_NATURAL_UNESCAPED_MESSAGE =
      "; `[(...)]` is a build error in a Tessera script: write `[[...]]`, which outputs a JSON value, or pass code another way";
  /** {@code %1$s} is the comment, {@code %2$s} the text after it that Thymeleaf drops. */
  static final String SCRIPT_NATURAL_COMMENT_MANUAL_MESSAGE =
      "%1$s in a script is a Thymeleaf natural-template comment, but it is not one inline followed by one literal (Thymeleaf drops %2$s after it, up to the next `;`, `,`, `)`, `}`, `]`, `//` or line end), so it was not rewritten; `[[...]]` inside a comment is a build error in Tessera: replace the comment and the text Thymeleaf drops with the inline, as in `var a = /*[[${x}]]*/ 'd';` to `var a = [[${x}]];`";
  /** {@code %s} is the inline. */
  static final String SCRIPT_INLINE_IN_JSON_STRING_MESSAGE =
      "`%s` inside a quoted string of a JSON script is a build error in Tessera (it writes a JSON value with its own quotes) and was not rewritten: make it the whole value, `{\"name\": [[...]]}`, and build any text around it in the model";

  /** {@code %1$s} is the link as a Markdown code span, {@code %2$s} its path variables and values. */
  static final String NULL_PATH_VARIABLE_MESSAGE =
      "link %1$s fails the render if a path variable is `null` (%2$s), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide section 3, step 4)";

  /**
   * Thymeleaf attributes that write an empty value for {@code null} (its non-removable attribute
   * processors), where Tessera omits the attribute.
   */
  static final Set<String> EMPTY_FOR_NULL = Compat.setOf("value", "href", "src", "action", "name", "type");
  /** {@code %1$s} is the attribute, {@code %2$s} its converted value as a code span, {@code %3$s} the HTML name. */
  static final String NULL_ATTRIBUTE_MESSAGE =
      "`%1$s` = %2$s: for a `null` value Thymeleaf wrote `%3$s=\"\"`, but Tessera omits the attribute; if it can be `null` and the empty attribute matters, write `x ?: ''` for a `String` or `x == null ? '' : '' + x` (guide section 4.3)";
  static final String TARGET = "target";
  /** {@code %1$s} is the attribute, {@code %2$s} its converted value as a code span. */
  static final String TARGET_MESSAGE =
      "`%1$s` = %2$s: `target` is not an inert-text attribute in Tessera, so a computed value other than a literal is a build error; choose between literals with a boolean model value, as in `t:target=\"newWindow ? '_blank' : '_self'\"` (guide section 4.4)";

  /**
   * Whether {@code expr} (a converted Tessera expression) is a string literal or a choice between
   * string literals ({@code c ? 'a' : 'b'}), which the compiler accepts in an attribute that is not
   * inert text.
   */
  static boolean literalChoice(String expr) {
    String e = Compat.strip(expr);
    if (e.startsWith("(") && Expressions.indexOfTopLevel(e.substring(1), ')') == e.length() - 2) {
      return literalChoice(e.substring(1, e.length() - 1));
    }
    if (STRING_LITERAL.matcher(e).matches()) {
      return true;
    }
    int q = Expressions.indexOfTopLevel(e, '?');
    if (q < 0) {
      return false;
    }
    String branches = e.substring(q + 1);
    int colon = Expressions.indexOfTopLevel(branches, ':');
    return colon > 0
        && literalChoice(branches.substring(0, colon))
        && literalChoice(branches.substring(colon + 1));
  }

  private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");

  /** {@code %s} is the selection as a code span. */
  static final String COMPOUND_SELECTION_MESSAGE =
      "%s is not converted: in Tessera `*{...}` holds one property path; write each path as its own selection, as in `*{firstName} + ' ' + *{lastName}` (guide section 4.3)";

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

  /** {@link Layouts}: page skeleton elements nested by a layout call, and other parse errors. */
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
  static final String PAGE_ELEMENT_FIX = "move %s to this page's `<%s>` on line %d";
  static final String ELEMENT_FIX = "move %s to the `<%s>` the page renders";
  /** How a page passes its {@code <head>} content to a layout; {@link Layouts}. */
  static final String HEAD_PARAMETER_FIX =
      "move it into a `<head t:remove=\"tag\">` in this page (beside its `<body>`) and pass `~{::head}` as the `%s` argument of `%s`";
  static final String NO_HEAD_PARAMETER_FIX =
      "`%s` takes no `<head>` content: give it a `Fragment head` parameter written as `<t:block t:replace=\"head\"></t:block>` in its `<head>`, move this into a `<head t:remove=\"tag\">` in this page and pass `~{::head}` for it (and `null` from the layout's other calls)";
  static final String PAGE_HEAD_FIX = "move it into this page's `<head>`";
  static final String LAYOUT_UNRESOLVED_MESSAGE =
      "the `<%s>` passed to `%s` was not checked (the fragment or its parameter is not among the converted templates); if the fragment inserts it inside its own `<%s>`, mark this `<%s>` `t:remove=\"tag\"` (guide section 4.8, Layouts)";
  static final String INNER_PAGE_ELEMENT_MESSAGE =
      "rewritten: deleted the tags of a `<%s>` inside a `<body>`; a browser ignored them, and a second `<%s>` is "
          + PARSE_ERROR_REJECTED + " (the page's `<%s>` is the layout's or its own)";
  static final String INNER_PAGE_ELEMENT_ATTRIBUTES_MESSAGE =
      "`%s` is inside a `<body>`: a browser ignores the tag and merges its attributes into the page's `<%s>`, and a second `<%s>` is "
          + PARSE_ERROR_REJECTED + ". Not rewritten, because deleting it would drop them. Fix: %s, then delete this `<%s>` and its `</%s>` (guide section 4.8, Layouts)";
  static final String HEAD_IN_BODY_MESSAGE =
      "`<head>` inside a `<body>`: a browser ignores its tags and keeps its elements in the body ("
          + PARSE_ERROR_REJECTED + "). Fix: delete its `<head>` and `</head>` and the elements the page's `<head>` already has; for each of the rest, %s";
  static final String FRAGMENT_DOCUMENT_REWRITE_MESSAGE =
      "rewritten: fragment `%s` wrote a whole document (`<html>`, `<head>`, `<body>`) into page bodies; a browser ignored those tags, and they are "
          + PARSE_ERROR_REJECTED + ". Deleted those tags and kept their content where a browser put it, in the page body%s";
  static final String FRAGMENT_HEAD_KEPT =
      ", including what the `<head>` held (%s): delete those the layout's `<head>` already has and move the rest there";
  static final String FRAGMENT_DOCUMENT_MESSAGE =
      "fragment `%s` writes a whole document (`<html>`, `<head>`, `<body>`) inside another element; where it is inserted these tags are "
          + PARSE_ERROR_REJECTED + ": keep only the body's content in the fragment, and move what its head needs to the layout's `<head>`";
  static final String BEFORE_LAYOUT_MESSAGE =
      "`<%s>` is written before the layout `%s` writes its doctype, so a browser ignores the doctype and renders the page in quirks mode ("
          + PARSE_ERROR_REJECTED + "). Fix: %s";
  static final String BLOCK_IN_P_MESSAGE =
      "`<%s>` inside the `<p>` of line %d closes that paragraph, so the `</p>` on line %d has no `<p>` to close ("
          + PARSE_ERROR_REJECTED + "): use a `<div>` instead of the `<p>`";
  static final String BETWEEN_HEAD_AND_BODY_MESSAGE =
      "`<%s>` between `</head>` and `<body>` is " + PARSE_ERROR_REJECTED + ": move it into the `<head>` or the `<body>`";
  static final String IN_TABLE_MESSAGE =
      "`<%s>` directly in a `<%s>`: a browser moves it in front of the table (" + PARSE_ERROR_REJECTED
          + "): put it in a `<td>` or `<th>`, or outside the table";
  static final String ATTRIBUTE_SPACE_MESSAGE =
      "no space before attribute `%s` (" + PARSE_ERROR_REJECTED + "): a space was added, which a browser reads the same";

  // ---- the CSRF token's closed shape (#550) ----

  static final String FORM = "form";
  static final String METHOD = "method";
  static final String ACTION = "action";
  static final String POST = "post";
  /** {@code %1$s} is the attribute name, {@code %2$s} its converted value as a code span. */
  static final String POST_ACTION_MESSAGE =
      "`%1$s` = %2$s on a `<form method=\"post\">` is not a link: Tessera adds the CSRF token to a POST form only"
          + " when its action is a same-origin link it can check, so any other computed action is a build error;"
          + " remove `%1$s` if the form posts to the page's own URL (a form without an action posts to the page"
          + " URL), or write the action as a link such as `@{/items/{id}(id=${item.id})}` (guide section 4.7)";

  /** {@code %1$s} is the inline, {@code %2$s} the variable it was hoisted into (#541). */
  static final String SCRIPT_HOISTED_MESSAGE =
      "`%1$s` in a script was moved into `var %2$s = %1$s;` at the top of the script and replaced by `%2$s`: in a"
          + " Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other"
          + " code; `%2$s` is a global variable in a classic script, so rename it if another script on the page uses"
          + " that name";
  /** Appended to {@link #SCRIPT_HOISTED_MESSAGE} when the inline was inside a quoted string. */
  static final String SCRIPT_HOISTED_STRING_MESSAGE =
      "; it was inside a quoted string, which is now joined with `+`: a non-string value renders as JSON (`null`,"
          + " not an empty string)";
  /** Appended to {@link #SCRIPT_HOISTED_MESSAGE} for an inline inside a block or function; {@code %s} is the variable. */
  static final String SCRIPT_HOISTED_DEFERRED_MESSAGE =
      "; it was inside a block or function: code that runs later, such as a callback or event handler, now"
          + " reads the global `%1$s` when it runs, and if the script is included more than once (a fragment per"
          + " row) each copy overwrites it, so every callback reads the last copy's value; give each copy its own"
          + " value, for example a number in a `data-*` attribute of the element it handles";
  /** {@code %s} is the inline. */
  static final String SCRIPT_INLINE_IN_REGEX_MESSAGE =
      "`%s` inside a JavaScript regular expression is a build error in Tessera and was not rewritten: declare the"
          + " value at the top of the script (`var v = [[...]];`) and build the pattern with `new RegExp(v)`";
  /** {@code %s} is the inline. */
  static final String SCRIPT_INLINE_IN_TEMPLATE_MESSAGE =
      "`%s` inside a template literal is a build error in Tessera and was not rewritten: declare the value at the"
          + " top of the script (`var v = [[...]];`) and write `${v}`";
  /** {@code %s} is the directive. */
  static final String SCRIPT_DIRECTIVE_MESSAGE =
      "%s no longer starts the script, because values were moved into declarations above it, so it no longer"
          + " applies and the script no longer runs in strict mode; put it at the start of the functions that need it";
}
