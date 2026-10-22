package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.runtime.SafeHtml;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * Text inlining: {@code [[expr]]} (escaped for the text node's context: TEXT via
 * {@code Escapers.text}, SCRIPT via {@code Escapers.json}) and {@code [(expr)]} (SafeHtml only,
 * TEXT context only). STYLE/FORBIDDEN contexts with inlined expressions are build errors.
 *
 * <p>Also holds the output helpers shared with {@code t:text} and {@code t:utext}.
 */
public final class TextInlining {
  private TextInlining() {}

  private static final String ESCAPERS = "dev.tessera.runtime.Escapers";
  private static final String SAFE_HTML = SafeHtml.class.getName();

  /** Boxes and JDK value types {@code Escapers.json} serializes as a JSON scalar. */
  private static final Set<String> JSON_VALUES =
      Set.of(
          "java.lang.Boolean",
          "java.lang.Byte",
          "java.lang.Short",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Float",
          "java.lang.Double",
          "java.lang.Character",
          "java.lang.String",
          "java.lang.CharSequence",
          "java.math.BigDecimal",
          "java.math.BigInteger");

  /**
   * {@code java.time} types {@code Escapers.json} serializes as an ISO string. Must mirror the
   * java.time cases of {@code Escapers.writeJson} exactly (ZoneOffset is handled there by
   * {@code case ZoneId}); java.time enums (DayOfWeek, Month) take the enum path.
   */
  private static final Set<String> JSON_TIME =
      Set.of(
          "java.time.Instant",
          "java.time.LocalDate",
          "java.time.LocalTime",
          "java.time.LocalDateTime",
          "java.time.OffsetTime",
          "java.time.OffsetDateTime",
          "java.time.ZonedDateTime",
          "java.time.Year",
          "java.time.YearMonth",
          "java.time.MonthDay",
          "java.time.Duration",
          "java.time.Period",
          "java.time.ZoneId",
          "java.time.ZoneOffset");

  private static final Set<String> JSON_COLLECTIONS =
      Set.of("java.util.Collection", "java.util.List", "java.util.Set");

  /**
   * Statements that render {@code text}, or {@code null} if it has no inlined expressions (the
   * generator then emits it verbatim).
   */
  public static String render(CodegenContext ctx, Text text, Scope scope) {
    String raw = text.raw();
    if (!raw.contains("[[") && !raw.contains("[(")) {
      return null;
    }
    StringBuilder code = new StringBuilder();
    StringBuilder literal = new StringBuilder();
    boolean found = false;
    int i = 0;
    while (i < raw.length()) {
      boolean escaped = raw.startsWith("[[", i);
      if (escaped || raw.startsWith("[(", i)) {
        int end = findEnd(raw, i + 2, escaped ? "]]" : ")]");
        if (end >= 0) {
          found = true;
          flush(code, literal);
          SourcePos pos = text.pos().advance(raw, i + 2);
          code.append(inline(ctx, text.context(), raw.substring(i + 2, end), pos, escaped, scope));
          i = end + 2;
          continue;
        }
      }
      literal.append(raw.charAt(i));
      i++;
    }
    if (!found) {
      return null;
    }
    flush(code, literal);
    return code.toString();
  }

  /** Index of the first {@code close} at or after {@code from} outside a '...' string, or -1. */
  private static int findEnd(String raw, int from, String close) {
    boolean inString = false;
    for (int i = from; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '\'') {
          inString = false;
        }
      } else if (c == '\'') {
        inString = true;
      } else if (raw.startsWith(close, i)) {
        return i;
      }
    }
    return -1;
  }

  private static void flush(StringBuilder code, StringBuilder literal) {
    if (!literal.isEmpty()) {
      code.append("out.write(").append(JavaStrings.literal(literal.toString())).append(");\n");
      literal.setLength(0);
    }
  }

  private static String inline(
      CodegenContext ctx, OutputContext context, String source, SourcePos pos, boolean escaped, Scope scope) {
    String form = escaped ? "[[...]]" : "[(...)]";
    if (context.escaper() == null) {
      ctx.diagnostics().error(pos, form + " is not allowed " + where(context));
      return "";
    }
    if (!escaped && context != OutputContext.TEXT) {
      ctx.diagnostics()
          .error(pos, "[(...)] is only allowed in element text; inside <script> use [[...]], which outputs JSON");
      return "";
    }
    Expr e = ctx.parser().parseExpression(source, pos, ctx.diagnostics());
    if (e == null) {
      return "";
    }
    return escaped ? valueStatement(ctx, e, context, scope) : safeHtmlStatement(ctx, e, form, scope);
  }

  /** Where an expression cannot go, for error messages. */
  static String where(OutputContext context) {
    return switch (context) {
      case STYLE -> "inside <style>: CSS cannot be escaped safely; compute a class name instead";
      case FORBIDDEN -> "inside a <script> whose type is not JavaScript or JSON (or is set by t:type)";
      default -> "in " + context + " context: move the expression into element text or a t: attribute";
    };
  }

  /**
   * A statement writing {@code e} escaped for {@code context} (TEXT or SCRIPT), or "" after
   * reporting an error. The value must be view-safe; in SCRIPT it must also be JSON-serializable.
   */
  static String valueStatement(CodegenContext ctx, Expr e, OutputContext context, Scope scope) {
    CompiledExpr c = ctx.expressions().compileOutput(e, scope);
    if (c == null) {
      return "";
    }
    if (context == OutputContext.SCRIPT) {
      if (!jsonable(ctx, c.type())) {
        ctx.diagnostics()
            .error(
                c.pos(),
                "cannot output a value of type " + c.type() + " inside <script>: only strings, numbers,"
                    + " booleans, enums, java.time values, and Optionals, collections, arrays and"
                    + " String-keyed maps of those can be serialized as JSON");
        return "";
      }
      return ESCAPERS + ".json(out, " + c.code() + ");\n";
    }
    if (isDeclared(c.type(), "java.util.Optional")) {
      String o = ctx.freshName("opt");
      return "{ java.util.Optional<?> " + o + " = " + c.code() + "; " + ESCAPERS + ".text(out, " + o
          + " == null ? null : " + o + ".orElse(null)); }\n";
    }
    return ESCAPERS + ".text(out, " + c.code() + ");\n";
  }

  /**
   * A statement writing {@code e} as raw HTML, or "" after reporting an error. {@code e} must
   * have static type {@code SafeHtml}; {@code form} names the construct in messages.
   */
  static String safeHtmlStatement(CodegenContext ctx, Expr e, String form, Scope scope) {
    if (e instanceof Expr.Message m) {
      ctx.diagnostics()
          .error(
              m.pos(),
              form + " needs a value of type " + SAFE_HTML + ", but a message #{...} is a String: use t:text"
                  + " or [[...]] (escaped), or pass trusted HTML as a SafeHtml parameter");
      return "";
    }
    CompiledExpr c = ctx.expressions().compile(e, scope);
    if (c == null) {
      return "";
    }
    if (!isDeclared(c.type(), SAFE_HTML)) {
      String type = c.type().getKind() == TypeKind.NULL ? "null" : c.type().toString();
      ctx.diagnostics()
          .error(
              c.pos(),
              form + " needs a value of type " + SAFE_HTML + ", but the expression has type " + type
                  + ": use t:text or [[...]] (escaped), or build a SafeHtml in trusted Java code");
      return "";
    }
    return ESCAPERS + ".html(out, " + c.code() + ");\n";
  }

  /** Whether {@code Escapers.json} can serialize every value of static type {@code t}. */
  private static boolean jsonable(CodegenContext ctx, TypeMirror t) {
    switch (t.getKind()) {
      case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, NULL:
        return true;
      case ARRAY:
        return jsonable(ctx, ((ArrayType) t).getComponentType());
      case WILDCARD: {
        TypeMirror bound = ((WildcardType) t).getExtendsBound();
        return bound != null && jsonable(ctx, bound);
      }
      case TYPEVAR:
        return jsonable(ctx, ctx.env().types().erasure(t));
      case DECLARED:
        break;
      default:
        return false;
    }
    TypeElement te = (TypeElement) ((DeclaredType) t).asElement();
    String name = te.getQualifiedName().toString();
    if (te.getKind() == ElementKind.ENUM || JSON_VALUES.contains(name) || JSON_TIME.contains(name)) {
      return true;
    }
    List<? extends TypeMirror> args = ((DeclaredType) t).getTypeArguments();
    if (name.equals("java.util.Optional") || JSON_COLLECTIONS.contains(name)) {
      return args.size() == 1 && jsonable(ctx, args.get(0));
    }
    if (name.equals("java.util.Map")) {
      return args.size() == 2 && isString(args.get(0)) && jsonable(ctx, args.get(1));
    }
    return false;
  }

  private static boolean isString(TypeMirror t) {
    if (t.getKind() == TypeKind.WILDCARD) {
      TypeMirror bound = ((WildcardType) t).getExtendsBound();
      return bound != null && isString(bound);
    }
    return isDeclared(t, "java.lang.String");
  }
}
