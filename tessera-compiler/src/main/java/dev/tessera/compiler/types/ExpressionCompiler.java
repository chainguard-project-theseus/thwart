package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.TypeRef;
import java.util.List;
import javax.lang.model.type.TypeMirror;

/**
 * Resolves and type-checks expressions against javac's model and emits Java code for them.
 * This is where principles 2 and 3 are enforced:
 *
 * <ul>
 *   <li>identifiers resolve only to {@link Scope} variables or registered function namespaces;
 *   <li>{@code a.b} / {@code a.b()} / {@code a.getB()} resolve only to a record component or a
 *       public no-arg {@code getB()}/{@code isB()} on a view-safe type ({@link ViewSafety}), or
 *       to a collection/String built-in; any other method call is an error;
 *   <li>{@code ns.fn(args)} resolves only through {@link FunctionRegistry};
 *   <li>never-view-safe types are rejected wherever they appear (params, results, arguments).
 * </ul>
 *
 * Messages and links are delegated to {@code MessageCompiler} / {@code LinkCompiler}, and the
 * reserved {@code fields} namespace to {@code FieldsCompiler}. All
 * methods report errors with the expression's position and return {@code null} on failure.
 */
public final class ExpressionCompiler {
  private final CompilerEnv env;

  public ExpressionCompiler(CompilerEnv env) {
    this.env = env;
  }

  public CompilerEnv env() {
    return env;
  }

  /** Compiles a value expression. */
  public CompiledExpr compile(Expr expr, Scope scope) {
    throw new UnsupportedOperationException("TODO(#4): ExpressionCompiler.compile");
  }

  /**
   * Compiles a condition to Java code of type {@code boolean}: {@code boolean} as is, Boolean
   * null-safe, anything else via {@code dev.tessera.runtime.Conditions.isTrue}.
   */
  public CompiledExpr compileCondition(Expr expr, Scope scope) {
    throw new UnsupportedOperationException("TODO(#4): ExpressionCompiler.compileCondition");
  }

  /**
   * Element type for {@code t:each} over {@code iterable}: {@code T} for {@code Iterable<T>}
   * and {@code T[]}, {@code Map.Entry<K,V>} for {@code Map<K,V>}. Reports and returns null
   * otherwise.
   */
  public TypeMirror elementType(CompiledExpr iterable) {
    throw new UnsupportedOperationException("TODO(#4): ExpressionCompiler.elementType");
  }

  /**
   * Resolves a declared parameter type. Simple names resolve against {@code imports}
   * ({@code t:import} entries), then the implicit imports {@code java.lang}, {@code java.util},
   * {@code java.time}, {@code java.math}, {@code dev.tessera.runtime}. Parameters must be
   * view-safe, {@code SafeHtml}, {@code TrustedUrl} or {@code Fragment}.
   */
  public TypeMirror resolveType(TypeRef ref, List<String> imports) {
    throw new UnsupportedOperationException("TODO(#4): ExpressionCompiler.resolveType");
  }

  /** Checks that {@code type} may be output (view-safe or primitive) and reports at {@code pos} if not. */
  public boolean checkOutputType(TypeMirror type, SourcePos pos) {
    throw new UnsupportedOperationException("TODO(#4): ExpressionCompiler.checkOutputType");
  }
}
