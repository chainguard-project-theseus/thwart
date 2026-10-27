package dev.tessera.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
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
  static final String SCRIPT_INLINE_IN_STRING_MESSAGE =
      "`%s` inside a quoted JavaScript string is a build error in Tessera (it writes a JSON value with its own quotes); rewrite as `\"text \" + %s + \" text\"`";

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

  // ---- computed attribute names the compiler does not know (#330, #331) ----

  /**
   * {@code t:}/{@code th:} local names that are processors, not the name of an attribute to set:
   * Tessera's ({@code AttributeProcessors}) and Thymeleaf's that Tessera lacks (reported elsewhere
   * or by the compiler).
   */
  static final Set<String> PROCESSORS = Compat.setOf(
      "params", "import", "fragment", "insert", "replace", "each", "if", "unless", "switch", "case", "object",
      "with", "attr", "field", "classappend", "errorclass", "text", "utext", "errors", "remove",
      "block", "inline", "include", "substituteby", "ref", "assert", "attrappend", "attrprepend", "alt-title",
      "lang-xmllang", "styleappend");

  /** A name the compiler classifies at all ({@code Contexts}' {@code PLAIN_NAME}); others have their own error. */
  static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]*");
  /** A component prop on a custom element. */
  static final Pattern CUSTOM_ELEMENT_PROP = Pattern.compile("[a-z][a-z0-9]*");
  /** The framework names the compiler classifies by prefix ({@code Contexts.isFrameworkDirective}). */
  static final List<String> FRAMEWORK_PREFIXES = Compat.listOf("v-", "x-", "hx-on", "ng-", "ng:", "ng_");
  /** The framework names the compiler classifies by name: hyperscript and htmx script, URL and boost names. */
  static final Set<String> FRAMEWORK_NAMES = Compat.setOf(
      "_", "script", "hx-vals", "hx-vars", "hx-headers", "hx-request", "hx-trigger",
      "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete", "sse-connect", "ws-connect",
      "hx-sse", "hx-ws", "hx-boost", "hx-push-url", "hx-replace-url");
  /**
   * htmx attributes that are unknown names to the compiler and that htmx reads the same with a
   * {@code data-} prefix (its {@code getAttributeValue} reads {@code hx-x} or {@code data-hx-x}):
   * {@code th:hx-target} becomes {@code t:data-hx-target}. Only the htmx 2 reference names whose
   * value is a selector, swap style, text or option list, never script; the script ones
   * ({@code hx-vals}, {@code hx-trigger}, ...) are classified by the compiler with or without
   * {@code data-}.
   */
  static final Set<String> HTMX_DATA_EQUIVALENT = Compat.setOf(
      "hx-target", "hx-swap", "hx-swap-oob", "hx-select", "hx-select-oob", "hx-confirm", "hx-prompt",
      "hx-indicator", "hx-include", "hx-disabled-elt", "hx-sync", "hx-params", "hx-encoding");

  /**
   * The standard HTML, SVG, MathML and ARIA attribute names: the compiler's
   * {@code dev/tessera/compiler/html/attributes.txt}, which the build copies next to this class.
   */
  static final Set<String> KNOWN_ATTRIBUTES = loadKnownAttributes();

  private static Set<String> loadKnownAttributes() {
    try (InputStream in = ConversionRules.class.getResourceAsStream("attributes.txt")) {
      if (in == null) {
        throw new IllegalStateException("dev/tessera/migrate/attributes.txt is missing");
      }
      Set<String> names = new HashSet<>();
      for (String line : new String(Compat.readAllBytes(in), StandardCharsets.UTF_8).split("\\r?\\n")) {
        if (!line.isEmpty() && !line.startsWith("#")) {
          int space = line.indexOf(' ');
          names.add(space < 0 ? line : line.substring(0, space));
        }
      }
      return Collections.unmodifiableSet(names);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read dev/tessera/migrate/attributes.txt", e);
    }
  }

  private static final String UNKNOWN_NAME =
      "`%2$s` is not a known attribute, and a computed attribute must be a standard HTML, SVG, MathML or ARIA"
          + " attribute, a `data-*` attribute or a plain prop on a custom element (a build error in Tessera)";
  /** {@code %1$s} the attribute, {@code %2$s} its name, {@code %3$s} the attribute with {@code data-}. */
  static final String UNKNOWN_NAME_MESSAGE =
      "`%1$s`: " + UNKNOWN_NAME + "; if it is data for your script, write `%3$s`; if a framework reads it, write it as a static attribute";
  /** {@code %1$s} the attribute, {@code %2$s} the new attribute, {@code %3$s} its old name. */
  static final String UNKNOWN_NAME_REWRITTEN_MESSAGE =
      "`%1$s` was rewritten to `%2$s`: `%3$s` is not a known attribute, which is a build error for a computed attribute in Tessera, and htmx reads `data-%3$s` the same way";
  /** {@code %1$s} the entry's name, {@code %2$s} the attribute, {@code %3$s} the name with {@code data-}. */
  static final String UNKNOWN_NAME_IN_ATTR_MESSAGE =
      "`%1$s` in `%2$s` is not a known attribute, and a computed attribute must be a standard HTML, SVG, MathML or ARIA"
          + " attribute, a `data-*` attribute or a plain prop on a custom element (a build error in Tessera); if it is"
          + " data for your script, write `%3$s`; if a framework reads it, write it as a static attribute";
  static final String UNKNOWN_NAME_IN_ATTR_REWRITTEN_MESSAGE =
      "`%1$s` in `%2$s` was rewritten to `%3$s`: `%1$s` is not a known attribute, which is a build error for a computed attribute in Tessera, and htmx reads `%3$s` the same way";

  /** {@code %s} is the token the compiler's data-prelude rule rejects (#332). */
  static final String SCRIPT_DATA_PRELUDE_MESSAGE =
      "%s before the last `[[...]]` of a `<script>` is a build error in Tessera: the compiler reads the code before the last value only with a lexer that has no ambiguous tokens, so it can prove where each value goes; move the `[[...]]` above the code (declare the data first, `var data = [[...]];`), or write the value into a `<script type=\"application/json\">` and read it with `JSON.parse`";
}
