package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;
import java.util.List;

/**
 * Expression AST (grammar in docs/mvp-design.md). Purely syntactic: the parser does not know
 * which identifiers are variables or function namespaces; the {@code ExpressionCompiler}
 * decides. Every node carries the template position of its first character.
 */
public sealed interface Expr {

  SourcePos pos();

  /** {@code 'text'}, {@code 42}, {@code 42L}, {@code 1.5}, {@code true}, {@code null}. {@code value} is a String, Integer, Long, Double, Boolean or null. */
  record Literal(Object value, SourcePos pos) implements Expr {}

  /** An identifier: a parameter, local ({@code t:each}/{@code t:with}) or function namespace. */
  record Var(String name, SourcePos pos) implements Expr {}

  /** Root identifier inside {@code *{...}}: a property of the current {@code t:object}. {@code *{a.b}} is {@code Property(SelectionVar(a), b)}. */
  record SelectionVar(String name, SourcePos pos) implements Expr {}

  /** {@code target.name} or {@code target?.name}. */
  record Property(Expr target, String name, boolean nullSafe, SourcePos pos) implements Expr {}

  /** {@code target.name(args)} / {@code target?.name(args)}. Also {@code ns.fn(args)} before resolution. */
  record MethodCall(Expr target, String name, List<Expr> args, boolean nullSafe, SourcePos pos) implements Expr {
    public MethodCall {
      args = List.copyOf(args);
    }
  }

  /** {@code target[index]} on a List, array or Map. */
  record Index(Expr target, Expr index, SourcePos pos) implements Expr {}

  /** {@code #ns.fn(args)} (Thymeleaf utility-object form). */
  record FunctionCall(String namespace, String name, List<Expr> args, SourcePos pos) implements Expr {
    public FunctionCall {
      args = List.copyOf(args);
    }
  }

  enum UnaryOp {
    NOT,
    NEGATE
  }

  /** {@code !x}, {@code not x}, {@code -x}. */
  record Unary(UnaryOp op, Expr operand, SourcePos pos) implements Expr {}

  enum BinaryOp {
    OR,
    AND,
    EQ,
    NE,
    LT,
    GT,
    LE,
    GE,
    ADD,
    SUB,
    MUL,
    DIV,
    MOD
  }

  /** Binary operator; textual aliases ({@code and}, {@code gt}, ...) map to the same ops. */
  record Binary(BinaryOp op, Expr left, Expr right, SourcePos pos) implements Expr {}

  /** {@code cond ? then : otherwise}; {@code otherwise} is null for {@code cond ? then}. */
  record Conditional(Expr condition, Expr then, Expr otherwise, SourcePos pos) implements Expr {}

  /** {@code value ?: fallback}. */
  record Elvis(Expr value, Expr fallback, SourcePos pos) implements Expr {}

  /** {@code #{key(args)}}. The key is always a literal. */
  record Message(String key, List<Expr> args, SourcePos pos) implements Expr {
    public Message {
      args = List.copyOf(args);
    }
  }

  /** {@code @{/path/{id}(id=x, q=y)}}. The path is always a literal. */
  record Link(String path, List<NamedArg> params, SourcePos pos) implements Expr {
    public Link {
      params = List.copyOf(params);
    }
  }

  /** {@code name=value} inside a link. */
  record NamedArg(String name, Expr value, SourcePos pos) {}

  /** {@code |Hello ${name}!|}: parts are String Literals and expressions, in order. */
  record Interpolation(List<Expr> parts, SourcePos pos) implements Expr {
    public Interpolation {
      parts = List.copyOf(parts);
    }
  }

  /** {@code ~{template :: name(args)}} used as a value (a fragment argument). */
  record FragmentExpr(FragmentRef ref, SourcePos pos) implements Expr {}
}
