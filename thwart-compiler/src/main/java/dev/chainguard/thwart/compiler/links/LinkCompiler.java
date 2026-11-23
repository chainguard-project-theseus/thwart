package dev.chainguard.thwart.compiler.links;

import dev.chainguard.thwart.compiler.DiagnosticSink;
import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.expr.Assignment;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.html.HtmlParser;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.compiler.types.ExpressionCompiler;
import dev.chainguard.thwart.compiler.types.Scope;
import dev.chainguard.thwart.runtime.internal.UrlEncoding;
import dev.chainguard.thwart.runtime.internal.Checks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Compiles {@code @{/path/{id}(id=x, q=y)}} to string concatenation (type String),
 * {@code ctx.links().encode(ctx.links().contextPath() + "/path/" + UrlEncoding.pathVariable("id", x)
 * + "?q=" + UrlEncoding.queryValue(y))}, with the semantics {@link dev.chainguard.thwart.runtime.Links}
 * documents. Primitive values are concatenated directly; a value whose type may be a collection or
 * array goes through {@code UrlEncoding.pathVariables}/{@code queryValues}. A path variable used
 * twice evaluates its value twice. Reports {@code {var}} placeholders without a matching
 * parameter, duplicate parameter names, and paths with schemes other than http/https. A parameter
 * written without a value ({@code @{/cart(add)}}) renders as the bare name; it cannot fill a path
 * variable.
 *
 * <p>A {@code {var}} in the scheme or host of an absolute or protocol-relative link is also an
 * error: path-variable encoding keeps letters, so a variable there would let data choose the
 * scheme or the host the URL points to.
 *
 * <p>A browser reads {@code \} as {@code /} and ignores leading control characters and tab, CR
 * and LF, so a path such as {@code /\host/x} is protocol-relative. Only the explicit
 * {@code //host/...} form may be protocol-relative; any other path a browser reads as one is an
 * error, as is a path starting with a single {@code \} ({@link #checkShape}).
 */
public final class LinkCompiler {
  private LinkCompiler() {}

  public static CompiledExpr compile(Expr.Link link, Scope scope, ExpressionCompiler expressions) {
    DiagnosticSink diagnostics = expressions.env().diagnostics();
    String path = link.path();
    boolean ok = checkSchemeAndHost(link, diagnostics);
    List<String> vars = placeholders(path);

    // Each parameter's compiled value: null for one written without a value, or for one whose value
    // failed to compile (then ok is false, and the !ok return below keeps it out of the output).
    Map<String, CompiledExpr> params = new LinkedHashMap<>();
    for (Assignment p : link.params()) {
      if (params.containsKey(p.name())) {
        diagnostics.error(p.pos(), "duplicate link parameter '" + p.name() + "' in @{" + path + "}");
        ok = false;
      }
      if (p.value() == null) {
        if (vars.contains(p.name())) {
          diagnostics.error(
              p.pos(),
              "link parameter '" + p.name() + "' fills path variable {" + p.name() + "} in @{" + path
                  + "} and needs a value: write (" + p.name() + "=...)");
          ok = false;
        }
        params.put(p.name(), null);
        continue;
      }
      CompiledExpr c = expressions.compileOutput(p.value(), scope);
      if (c == null) {
        ok = false; // keep the name: the parameter is written, its value is what failed (#914)
      }
      params.put(p.name(), c);
    }

    for (String var : vars) {
      if (!params.containsKey(var)) {
        diagnostics.error(
            link.pos(),
            "link path variable {" + var + "} in @{" + path + "} has no parameter: add (" + var + "=...)");
        ok = false;
      }
    }
    if (!ok) {
      return null;
    }

    Concat url = new Concat();
    if (path.startsWith("/") && !path.startsWith("//")) {
      url.code("ctx.links().contextPath()");
    }
    // The path up to its fragment with each {var} filled, then the query, then the fragment.
    int hash = fragmentStart(path);
    boolean pathQuery = appendPath(url, path.substring(0, hash), params, expressions);
    List<String> query = new ArrayList<>();
    for (String name : params.keySet()) {
      if (!vars.contains(name)) {
        query.add(name);
      }
    }
    // A collection first may render nothing, so its '?' is chosen at render time.
    boolean wrap = !pathQuery && !query.isEmpty() && params.get(query.get(0)) != null
        && !scalar(params.get(query.get(0)).type(), expressions);
    Concat q = wrap ? new Concat() : url;
    for (String name : query) {
      String sep = name.equals(query.get(0)) && !pathQuery && !wrap ? "?" : "&";
      appendQuery(q, sep, name, params.get(name), expressions);
    }
    if (wrap) {
      url.code(ENCODING + ".firstQuery(" + q.build() + ")");
    }
    appendPath(url, path.substring(hash), params, expressions);
    return new CompiledExpr(
        "ctx.links().encode(" + url.build() + ")",
        expressions.env().elements().getTypeElement("java.lang.String").asType(),
        link.pos());
  }

  private static final String ENCODING = UrlEncoding.class.getName();

  /** The index of the first {@code #} outside a {@code {var}}, or the length of {@code path}. */
  private static int fragmentStart(String path) {
    int i = 0;
    while (i < path.length() && path.charAt(i) != '#') {
      int close = path.charAt(i) == '{' ? path.indexOf('}', i + 1) : -1;
      i = close >= 0 ? close + 1 : i + 1;
    }
    return i;
  }

  /** Appends literal {@code part} with each {@code {var}} filled; whether its literal text has a {@code ?}. */
  private static boolean appendPath(
      Concat url, String part, Map<String, CompiledExpr> params, ExpressionCompiler expressions) {
    boolean query = false;
    int i = 0;
    while (i < part.length()) {
      char c = part.charAt(i);
      int close = c == '{' ? part.indexOf('}', i + 1) : -1;
      if (close >= 0) {
        String name = part.substring(i + 1, close);
        appendPathVariable(url, name, params.get(name), expressions);
        i = close + 1;
      } else {
        query |= c == '?';
        url.literal(String.valueOf(c));
        i++;
      }
    }
    return query;
  }

  private static void appendPathVariable(Concat url, String name, CompiledExpr c, ExpressionCompiler expressions) {
    TypeKind kind = c.type().getKind();
    if (kind.isPrimitive() && kind != TypeKind.CHAR) {
      // Digits, '-', '.', 'E', NaN, Infinity, true, false: unreserved, never empty or a dot segment.
      url.value("(" + c.code() + ")");
    } else if (kind == TypeKind.CHAR) {
      url.code(ENCODING + ".pathVariable(" + JavaStrings.literal(name) + ", java.lang.String.valueOf(" + c.code()
          + "))");
    } else {
      String method = scalar(c.type(), expressions) ? ".pathVariable(" : ".pathVariables(";
      url.code(ENCODING + method + JavaStrings.literal(name) + ", " + c.code() + ")");
    }
  }

  private static void appendQuery(Concat url, String sep, String name, CompiledExpr c, ExpressionCompiler expressions) {
    String encoded = UrlEncoding.queryParam(name);
    if (c == null) {
      url.literal(sep + encoded);
      return;
    }
    if (!scalar(c.type(), expressions)) {
      // Writes its own '&' before each pair.
      url.code(ENCODING + ".queryValues(" + JavaStrings.literal(encoded) + ", " + c.code() + ")");
      return;
    }
    url.literal(sep + encoded + "=");
    TypeKind kind = c.type().getKind();
    if (kind.isPrimitive() && kind != TypeKind.CHAR) {
      url.value("(" + c.code() + ")");
    } else if (kind == TypeKind.CHAR) {
      url.code(ENCODING + ".queryValue(java.lang.String.valueOf(" + c.code() + "))");
    } else {
      url.code(ENCODING + ".queryValue(" + c.code() + ")");
    }
  }

  /**
   * Whether a value of {@code type} is never a collection or array at render time: a primitive, or
   * a final class or enum that is not {@link Iterable}. Anything else goes through the runtime
   * helpers that check.
   */
  private static boolean scalar(TypeMirror type, ExpressionCompiler expressions) {
    if (type.getKind().isPrimitive()) {
      return true;
    }
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element e = ((DeclaredType) type).asElement();
    Types types = expressions.env().types();
    TypeMirror iterable = types.erasure(expressions.env().elements().getTypeElement("java.lang.Iterable").asType());
    return (e.getModifiers().contains(Modifier.FINAL) || e.getKind() == ElementKind.ENUM)
        && !types.isAssignable(types.erasure(type), iterable);
  }

  /** Java code concatenating literal text and String or primitive values, adjacent literals merged. */
  private static final class Concat {
    private final StringBuilder code = new StringBuilder();
    private final StringBuilder literal = new StringBuilder();

    void literal(String s) {
      literal.append(s);
    }

    /** A String-typed piece. */
    void code(String c) {
      flush();
      append(c);
    }

    /** A primitive piece: a String must come first so that {@code +} concatenates. */
    void value(String c) {
      flush();
      if (code.length() == 0) {
        append("\"\"");
      }
      append(c);
    }

    String build() {
      flush();
      return code.length() == 0 ? "\"\"" : code.toString();
    }

    private void flush() {
      if (literal.length() > 0) {
        append(JavaStrings.literal(literal.toString()));
        literal.setLength(0);
      }
    }

    private void append(String c) {
      code.append(code.length() == 0 ? "" : " + ").append(c);
    }
  }

  /** The names inside {@code {...}} in {@code path}, scanned like {@code DefaultLinks} does. */
  public static List<String> placeholders(String path) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (true) {
      int open = path.indexOf('{', i);
      int close = open < 0 ? -1 : path.indexOf('}', open + 1);
      if (close < 0) {
        return out;
      }
      out.add(path.substring(open + 1, close));
      i = close + 1;
    }
  }

  private static boolean checkSchemeAndHost(Expr.Link link, DiagnosticSink diagnostics) {
    String path = link.path();
    String scheme = scheme(path);
    if (scheme != null && !HtmlParser.asciiEqualsIgnoreCase(scheme, "http")
        && !HtmlParser.asciiEqualsIgnoreCase(scheme, "https")) {
      diagnostics.error(
          link.pos(),
          "link @{" + path + "} has scheme '" + scheme + ":': only http and https absolute links are"
              + " allowed; use a relative path, or a static attribute for another scheme");
      return false;
    }
    String authority = authority(path, scheme != null);
    if (authority != null && authority.indexOf('{') >= 0) {
      diagnostics.error(
          link.pos(),
          "link @{" + path + "} has a path variable in its host: write the host literally");
      return false;
    }
    return checkShape(link, diagnostics);
  }

  /**
   * Rejects a literal path that a browser reads as protocol-relative ({@link #browserForm} starts
   * with {@code //}) unless it is written exactly {@code //host/...}, and a path starting with a
   * single {@code \}.
   */
  private static boolean checkShape(Expr.Link link, DiagnosticSink diagnostics) {
    String path = link.path();
    if (browserForm(path).startsWith("//") && !path.startsWith("//")) {
      diagnostics.error(
          link.pos(),
          "link @{" + path + "} starts like a protocol-relative URL (a browser reads '\\' as '/'), so it"
              + " would load from another origin: write @{/...} for a path on this site, or @{//host/...}"
              + " if another origin is meant");
      return false;
    }
    if (path.startsWith("\\")) {
      diagnostics.error(
          link.pos(), "link @{" + path + "} starts with '\\', which a browser reads as '/': write '/' instead");
      return false;
    }
    return true;
  }

  /**
   * A literal link path that stays on the page's origin: it starts with {@code /} and a browser
   * does not read it as protocol-relative ({@code //host}, {@code /\host}, {@code /<tab>/host}).
   * The runtime's {@link Checks#isSameOriginUrl} rule for a path. Used for
   * refresh URLs, the link {@code action} of any form, a {@code formaction} that may receive the CSRF token and
   * {@code <base href>}, which must be same-origin. This checks only the literal; the context path
   * or a custom {@code Links} can still make the built URL protocol-relative, so the generated code
   * re-checks it with {@code Escapers.sameOriginLink}.
   */
  public static boolean isSameOriginPath(String path) {
    return Checks.isSameOriginPath(path);
  }

  /**
   * The start of {@code path} as a browser's URL parser sees it: leading chars {@code <= 0x20}
   * dropped, tab, CR and LF removed, and {@code \} read as {@code /}.
   */
  static String browserForm(String path) {
    int i = 0;
    while (i < path.length() && path.charAt(i) <= ' ') {
      i++;
    }
    return path.substring(i).replaceAll("[\t\r\n]", "").replace('\\', '/');
  }

  /**
   * The scheme of {@code path} (the text before a {@code :} that comes before any {@code /},
   * {@code ?} or {@code #}), with every char {@code <= 0x20} and {@code 0x7f} dropped anywhere
   * before the {@code :}; null if the path has no scheme. This is stricter than browsers (which
   * strip only leading C0/space and tab/CR/LF); the runtime {@code Escapers.url}/{@code
   * resourceUrl} re-check with the browser-accurate parser.
   */
  static String scheme(String path) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        return null;
      }
      if (c == ':') {
        return sb.toString();
      }
      if (c > 0x20 && c != 0x7f) {
        sb.append(c);
      }
    }
    return null;
  }

  /**
   * The authority of {@code path}, or null if there is none. Like browsers, treats {@code \} as
   * {@code /} and ignores tab, CR and LF. After an http(s) scheme any number of slashes (even
   * none: {@code https:host/x}) precede the authority; without a scheme it needs {@code //}.
   */
  private static String authority(String path, boolean hasScheme) {
    String rest = hasScheme ? path.substring(path.indexOf(':') + 1) : path;
    rest = HtmlParser.stripAnySpace(rest.replaceAll("[\t\r\n]", "").replace('\\', '/'));
    if (hasScheme) {
      rest = rest.replaceFirst("^/*", "");
    } else if (rest.startsWith("//")) {
      rest = rest.substring(2);
    } else {
      return null;
    }
    int end = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        end = i;
        break;
      }
    }
    return rest.substring(0, end);
  }
}
