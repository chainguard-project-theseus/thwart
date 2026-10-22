package dev.tessera.compiler.messages;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Test helper for the message and link compilers: parses and compiles an expression inside
 * javac (against {@link #java} sources and {@link #var} scope variables), then compiles the
 * emitted Java code in a second pass and runs it with a {@code RenderContext ctx}.
 */
public final class ExprRunner {
  private final Map<String, String> java = new LinkedHashMap<>();
  private final List<String[]> vars = new ArrayList<>();
  private String bundle;

  public static ExprRunner create() {
    return new ExprRunner();
  }

  public ExprRunner java(String qualifiedName, String source) {
    java.put(qualifiedName, source);
    return this;
  }

  /** A scope variable of (non-generic) {@code type}, initialized to Java code {@code init} when run. */
  public ExprRunner var(String name, String type, String init) {
    vars.add(new String[] {name, type, init});
    return this;
  }

  /** Checks message keys against a bundle with these {@code .properties} contents. */
  public ExprRunner bundle(String properties) {
    this.bundle = properties;
    return this;
  }

  /** The emitted code (null on error) and every diagnostic as {@code file:line:col: severity: msg}. */
  public record Result(String code, List<String> diagnostics) {
    public List<String> errors() {
      return diagnostics.stream().filter(d -> d.contains(": error: ")).toList();
    }

    public List<String> warnings() {
      return diagnostics.stream().filter(d -> d.contains(": warning: ")).toList();
    }
  }

  /** Compiles each expression (all as if in template {@code t.html}) and returns one result each. */
  public List<Result> compileAll(String... expressions) {
    MessageBundle messages = bundle == null ? MessageBundle.unchecked() : loadBundle();
    AtomicReference<List<Result>> out = new AtomicReference<>();
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    Compilation.Builder b = Compilation.builder();
    java.forEach(b::java);
    b.inProcessor(
            pe -> {
              try {
                Diagnostics diagnostics = new Diagnostics();
                CompilerEnv env =
                    new CompilerEnv(
                        pe.getElementUtils(), pe.getTypeUtils(), diagnostics, CompilerEnv.DEFAULT_PACKAGE, messages, List.of());
                ExpressionCompiler compiler = new ExpressionCompiler(env);
                Scope scope = Scope.empty();
                for (String[] v : vars) {
                  TypeMirror t =
                      v[1].equals("int")
                          ? pe.getTypeUtils().getPrimitiveType(TypeKind.INT)
                          : pe.getTypeUtils().erasure(pe.getElementUtils().getTypeElement(v[1]).asType());
                  scope = scope.with(new Variable(v[0], t, v[0]));
                }
                List<Result> results = new ArrayList<>();
                for (String text : expressions) {
                  int before = diagnostics.all().size();
                  Expr e = new ExpressionParser().parseExpression(text, new SourcePos("t.html", 1, 1), diagnostics);
                  CompiledExpr c = e == null ? null : compiler.compile(e, scope);
                  List<String> ds =
                      diagnostics.all().subList(before, diagnostics.all().size()).stream().map(Diagnostic::toString).toList();
                  results.add(new Result(c == null ? null : c.code(), ds));
                }
                out.set(results);
              } catch (RuntimeException ex) {
                failure.set(ex);
              }
            })
        .compile()
        .assertSuccess();
    if (failure.get() != null) {
      throw failure.get();
    }
    return out.get();
  }

  public Result compile(String expression) {
    return compileAll(expression).get(0);
  }

  /** Compiles {@code expression}, then runs its code with {@code RenderContext ctx = <ctxInit>}. */
  public Object eval(String expression, String ctxInit) {
    Result r = compile(expression);
    if (r.code() == null) {
      throw new AssertionError("expression did not compile: " + r.diagnostics());
    }
    StringBuilder src =
        new StringBuilder("package tessera.eval; public final class Eval { public static Object run() {\n");
    src.append("dev.tessera.runtime.RenderContext ctx = ").append(ctxInit).append(";\n");
    for (String[] v : vars) {
      src.append(v[1]).append(' ').append(v[0]).append(" = ").append(v[2]).append(";\n");
    }
    src.append("String r$ = ").append(r.code()).append(";\nreturn r$; } }");
    Compilation.Builder b = Compilation.builder();
    java.forEach(b::java);
    Compilation c = b.java("tessera.eval.Eval", src.toString()).compile();
    if (!c.success()) {
      throw new AssertionError("generated code does not compile:\n" + src + "\n" + c.errors());
    }
    try {
      return c.classLoader().loadClass("tessera.eval.Eval").getMethod("run").invoke(null);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private MessageBundle loadBundle() {
    try {
      Path dir = Files.createTempDirectory("tessera-bundle");
      Path base = dir.resolve("messages");
      Files.writeString(MessageBundle.file(base), bundle, StandardCharsets.UTF_8);
      MessageBundle m = MessageBundle.load(base);
      Files.delete(MessageBundle.file(base));
      Files.delete(dir);
      return m;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
