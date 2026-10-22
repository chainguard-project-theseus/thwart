package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.TypeRef;
import dev.tessera.compiler.messages.MessageBundle;
import dev.tessera.compiler.testing.Compilation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Test harness for the types package: runs code inside javac (via {@link Compilation#builder()}
 * and {@code inProcessor}) against model sources, and can compile and run the emitted Java code
 * in a second pass to check that it type-checks and evaluates as expected.
 */
final class TypesHarness {
  /** Model types shared by most tests. */
  static final Map<String, String> MODEL = new LinkedHashMap<>();

  static {
    MODEL.put("com.example.Status", "package com.example; public enum Status { ACTIVE, SUSPENDED }");
    MODEL.put(
        "com.example.Item",
        "package com.example; public record Item(String name, int qty, java.math.BigDecimal price) {}");
    MODEL.put(
        "com.example.User",
        """
        package com.example;
        import java.util.*;
        public record User(String name, int age, boolean admin, Status status, User manager,
            List<Item> items, Map<String, List<Item>> groups, String[] tags, Optional<String> nick,
            Boolean verified, Integer score, Map<Integer, String> byId) {
          public void delete() {}
          public String getDisplay() { return "D:" + name; }
          public boolean isAdult() { return age >= 18; }
          public static String getStatic() { return "s"; }
          public String getWithArg(int x) { return "x"; }
        }
        """);
    MODEL.put(
        "com.example.Account",
        """
        package com.example;
        @dev.tessera.runtime.TemplateView
        public class Account {
          public String getId() { return "a1"; }
          public boolean isActive() { return true; }
          public String secret() { return "s"; }
          public void close() {}
          public Thread getThread() { return null; }
          public Class<?> getType() { return Account.class; }
          public ClassLoader getLoader() { return null; }
          public Pojo getPojo() { return new Pojo(); }
          public String getURL() { return "u"; }
          public Threads getThreads() { return new Threads(); }
          public static class Threads extends java.util.ArrayList<Thread> {}
        }
        """);
    MODEL.put("com.example.Pojo", "package com.example; public class Pojo { public String getName() { return \"p\"; } }");
  }

  static final SourcePos P = new SourcePos("t.html", 1, 1);

  static SourcePos at(int line, int col) {
    return new SourcePos("t.html", line, col);
  }

  // ------------------------------------------------------------- Expr builders

  static Expr lit(Object v) {
    return new Expr.Literal(v, P);
  }

  static Expr v(String name) {
    return new Expr.Var(name, P);
  }

  static Expr prop(Expr target, String name) {
    return new Expr.Property(target, name, false, P);
  }

  static Expr safe(Expr target, String name) {
    return new Expr.Property(target, name, true, P);
  }

  static Expr call(Expr target, String name, Expr... args) {
    return new Expr.MethodCall(target, name, List.of(args), false, P);
  }

  static Expr idx(Expr target, Expr index) {
    return new Expr.Index(target, index, P);
  }

  static Expr fn(String ns, String name, Expr... args) {
    return new Expr.FunctionCall(ns, name, List.of(args), P);
  }

  static Expr bin(Expr.BinaryOp op, Expr l, Expr r) {
    return new Expr.Binary(op, l, r, P);
  }

  static Expr not(Expr e) {
    return new Expr.Unary(Expr.UnaryOp.NOT, e, P);
  }

  static Expr neg(Expr e) {
    return new Expr.Unary(Expr.UnaryOp.NEGATE, e, P);
  }

  static Expr cond(Expr c, Expr t, Expr o) {
    return new Expr.Conditional(c, t, o, P);
  }

  static Expr elvis(Expr a, Expr b) {
    return new Expr.Elvis(a, b, P);
  }

  /** Parses {@code java.util.Map<String, java.util.List<Item>>[]} into a TypeRef (test helper only). */
  static TypeRef ref(String s) {
    int[] i = {0};
    TypeRef r = parseRef(s.replace(" ", ""), i);
    if (i[0] != s.replace(" ", "").length()) {
      throw new IllegalArgumentException(s);
    }
    return r;
  }

  private static TypeRef parseRef(String s, int[] i) {
    int start = i[0];
    while (i[0] < s.length() && "<>,[]".indexOf(s.charAt(i[0])) < 0) {
      i[0]++;
    }
    String name = s.substring(start, i[0]);
    List<TypeRef> args = new ArrayList<>();
    if (i[0] < s.length() && s.charAt(i[0]) == '<') {
      do {
        i[0]++;
        args.add(parseRef(s, i));
      } while (s.charAt(i[0]) == ',');
      i[0]++; // '>'
    }
    int dims = 0;
    while (i[0] + 1 < s.length() && s.charAt(i[0]) == '[') {
      dims++;
      i[0] += 2;
    }
    return new TypeRef(name, args, dims, P);
  }

  // ------------------------------------------------------------- fixture

  /** State available inside javac. */
  static final class Fixture {
    final ProcessingEnvironment pe;
    final Diagnostics diagnostics = new Diagnostics();
    final CompilerEnv env;
    private ExpressionCompiler compiler;
    Scope scope = Scope.empty();

    Fixture(ProcessingEnvironment pe, List<String> namespaces) {
      this.pe = pe;
      List<TypeElement> ns = namespaces.stream().map(n -> pe.getElementUtils().getTypeElement(n)).toList();
      this.env =
          new CompilerEnv(
              pe.getElementUtils(), pe.getTypeUtils(), diagnostics, CompilerEnv.DEFAULT_PACKAGE, MessageBundle.unchecked(), ns);
    }

    ExpressionCompiler compiler() {
      if (compiler == null) {
        compiler = new ExpressionCompiler(env);
      }
      return compiler;
    }

    ViewSafety safety() {
      return new ViewSafety(env);
    }

    /** A type from a source-like string, without view-safety checks (unlike resolveType). */
    TypeMirror type(String s) {
      return mirror(ref(s));
    }

    private TypeMirror mirror(TypeRef r) {
      TypeMirror t;
      switch (r.name()) {
        case "int" -> t = pe.getTypeUtils().getPrimitiveType(TypeKind.INT);
        case "long" -> t = pe.getTypeUtils().getPrimitiveType(TypeKind.LONG);
        case "boolean" -> t = pe.getTypeUtils().getPrimitiveType(TypeKind.BOOLEAN);
        case "double" -> t = pe.getTypeUtils().getPrimitiveType(TypeKind.DOUBLE);
        default -> {
          TypeElement te = pe.getElementUtils().getTypeElement(r.name());
          if (te == null) {
            throw new IllegalArgumentException("no type " + r.name());
          }
          t =
              r.typeArgs().isEmpty()
                  ? pe.getTypeUtils().erasure(te.asType())
                  : pe.getTypeUtils().getDeclaredType(te, r.typeArgs().stream().map(this::mirror).toArray(TypeMirror[]::new));
        }
      }
      for (int d = 0; d < r.arrayDims(); d++) {
        t = pe.getTypeUtils().getArrayType(t);
      }
      return t;
    }

    Fixture var(String name, String type) {
      scope = scope.with(new Variable(name, type(type), name));
      return this;
    }

    CompiledExpr compile(Expr e) {
      return compiler().compile(e, scope);
    }

    List<String> errors() {
      return diagnostics.all().stream()
          .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
          .map(Diagnostic::toString)
          .toList();
    }
  }

  // ------------------------------------------------------------- runners

  private final Map<String, String> java = new LinkedHashMap<>(MODEL);
  private final List<String> namespaces = new ArrayList<>();
  private final List<String[]> locals = new ArrayList<>();

  static TypesHarness create() {
    return new TypesHarness();
  }

  TypesHarness java(String qualifiedName, String source) {
    java.put(qualifiedName, source);
    return this;
  }

  /** Registers a {@code @TemplateFunctions} class from the test sources. */
  TypesHarness functions(String qualifiedName) {
    namespaces.add(qualifiedName);
    return this;
  }

  /** A scope variable of {@code type}, initialized to Java expression {@code init} when evaluating. */
  TypesHarness local(String name, String type, String init) {
    locals.add(new String[] {name, type, init});
    return this;
  }

  /** Runs {@code body} inside javac; assertion failures inside are rethrown here. */
  void run(Consumer<Fixture> body) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Compilation.Builder b = Compilation.builder();
    java.forEach(b::java);
    b.inProcessor(
            pe -> {
              try {
                Fixture f = new Fixture(pe, namespaces);
                for (String[] l : locals) {
                  f.var(l[0], l[1]);
                }
                body.accept(f);
              } catch (Throwable t) {
                failure.set(t);
              }
            })
        .compile()
        .assertSuccess();
    if (failure.get() instanceof RuntimeException e) {
      throw e;
    }
    if (failure.get() instanceof Error e) {
      throw e;
    }
  }

  /** Result of compiling one expression in the first pass. */
  record Result(String code, String type, List<String> errors) {}

  Result compile(Expr e) {
    return compile(f -> f.compile(e));
  }

  Result compile(Function<Fixture, CompiledExpr> body) {
    AtomicReference<Result> out = new AtomicReference<>();
    run(
        f -> {
          CompiledExpr c = body.apply(f);
          out.set(
              new Result(
                  c == null ? null : c.code(),
                  c == null ? null : (c.type().getKind() == TypeKind.NULL ? "java.lang.Object" : c.type().toString()),
                  f.errors()));
        });
    return out.get();
  }

  /** Compiles {@code e}, then compiles {@code TYPE r = CODE;} with the locals and returns r. */
  Object eval(Expr e) {
    Result r = compile(e);
    if (r.code() == null) {
      throw new AssertionError("expression did not compile: " + r.errors());
    }
    return evalCode(r);
  }

  Object evalCode(Result r) {
    StringBuilder src = new StringBuilder("package tessera.eval; public final class Eval { public static Object run() {\n");
    for (String[] l : locals) {
      src.append(l[1]).append(' ').append(l[0]).append(" = ").append(l[2]).append(";\n");
    }
    src.append(r.type()).append(" r$ = ").append(r.code()).append(";\nreturn r$; } }");
    Compilation.Builder b = Compilation.builder();
    java.forEach(b::java);
    Compilation c = b.java("tessera.eval.Eval", src.toString()).compile();
    if (!c.success()) {
      throw new AssertionError("generated code does not compile:\n" + src + "\n" + c.errors());
    }
    try {
      Method m = c.classLoader().loadClass("tessera.eval.Eval").getMethod("run");
      return m.invoke(null);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }
}
