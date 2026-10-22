package dev.tessera.compiler.links;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.expr.Assignment;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles {@code @{/path/{id}(id=x, q=y)}} to
 * {@code ctx.links().build("/path/{id}", new String[] {"id", "q"}, new Object[] {x, y})}
 * (type String). Reports {@code {var}} placeholders without a matching parameter, duplicate
 * parameter names, and paths with schemes other than http/https.
 *
 * <p>A {@code {var}} in the scheme or host of an absolute or protocol-relative link is also an
 * error: path-variable encoding keeps letters, so a variable there would let data choose the
 * scheme or the host the URL points to.
 */
public final class LinkCompiler {
  private LinkCompiler() {}

  public static CompiledExpr compile(Expr.Link link, Scope scope, ExpressionCompiler expressions) {
    DiagnosticSink diagnostics = expressions.env().diagnostics();
    String path = link.path();
    boolean ok = checkSchemeAndHost(link, diagnostics);

    Set<String> names = new LinkedHashSet<>();
    List<String> values = new ArrayList<>();
    for (Assignment p : link.params()) {
      if (!names.add(p.name())) {
        diagnostics.error(p.pos(), "duplicate link parameter '" + p.name() + "' in @{" + path + "}");
        ok = false;
      }
      CompiledExpr c = expressions.compileOutput(p.value(), scope);
      if (c == null) {
        ok = false;
        continue;
      }
      values.add(c.code());
    }

    for (String var : placeholders(path)) {
      if (!names.contains(var)) {
        diagnostics.error(
            link.pos(),
            "link path variable {" + var + "} in @{" + path + "} has no parameter: add (" + var + "=...)");
        ok = false;
      }
    }
    if (!ok) {
      return null;
    }

    StringBuilder code = new StringBuilder("ctx.links().build(").append(JavaStrings.literal(path));
    code.append(", new java.lang.String[] {");
    code.append(String.join(", ", names.stream().map(JavaStrings::literal).toList()));
    code.append("}, new java.lang.Object[] {").append(String.join(", ", values)).append("})");
    return new CompiledExpr(
        code.toString(), expressions.env().elements().getTypeElement("java.lang.String").asType(), link.pos());
  }

  /** The names inside {@code {...}} in {@code path}, scanned like {@code DefaultLinks} does. */
  static List<String> placeholders(String path) {
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
    if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
      diagnostics.error(
          link.pos(),
          "link @{" + path + "} has scheme '" + scheme + ":': only http and https absolute links are"
              + " allowed; use a relative path, or pass a TrustedUrl instead of a link");
      return false;
    }
    String authority = authority(path, scheme != null);
    if (authority != null && authority.indexOf('{') >= 0) {
      diagnostics.error(
          link.pos(),
          "link @{" + path + "} has a path variable in its host: write the host literally");
      return false;
    }
    return true;
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
    rest = rest.replaceAll("[\t\r\n]", "").replace('\\', '/').stripLeading();
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
