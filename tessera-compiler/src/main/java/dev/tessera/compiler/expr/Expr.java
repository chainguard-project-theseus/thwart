package dev.tessera.compiler.expr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import java.util.List;
import java.util.Objects;

/**
 * Expression AST (grammar in docs/architecture.md). Purely syntactic: the parser does not know
 * which identifiers are variables or function namespaces; the {@code ExpressionCompiler}
 * decides. Every node carries the template position of its first token. Infix and postfix nodes
 * ({@code Binary}, {@code Conditional}, {@code Elvis}, {@code Index}, {@code Property},
 * {@code MethodCall}) take their left operand's position. Parentheses and {@code ${...}} wrappers
 * are transparent, and {@code *{name}} points at {@code name}.
 */
public interface Expr {

  SourcePos pos();

  /** {@code 'text'}, {@code 42}, {@code 42L}, {@code 1.5}, {@code true}, {@code null}. {@code value} is a String, Integer, Long, Double, Boolean or null. */
  final class Literal implements Expr {
    private final Object value;
    private final SourcePos pos;

    public Literal(Object value, SourcePos pos) {
      this.value = value;
      this.pos = pos;
    }

    public Object value() {
      return value;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Literal)) {
        return false;
      }
      Literal that = (Literal) o;
      return Objects.equals(value, that.value)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(value);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Literal[value=" + value + ", pos=" + pos + "]";
    }
  }

  /** An identifier: a parameter, local ({@code t:each}/{@code t:with}) or function namespace. */
  final class Var implements Expr {
    private final String name;
    private final SourcePos pos;

    public Var(String name, SourcePos pos) {
      this.name = name;
      this.pos = pos;
    }

    public String name() {
      return name;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Var)) {
        return false;
      }
      Var that = (Var) o;
      return Objects.equals(name, that.name)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Var[name=" + name + ", pos=" + pos + "]";
    }
  }

  /** Root identifier inside {@code *{...}}: a property of the current {@code t:object}. {@code *{a.b}} is {@code Property(SelectionVar(a), b)}. */
  final class SelectionVar implements Expr {
    private final String name;
    private final SourcePos pos;

    public SelectionVar(String name, SourcePos pos) {
      this.name = name;
      this.pos = pos;
    }

    public String name() {
      return name;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof SelectionVar)) {
        return false;
      }
      SelectionVar that = (SelectionVar) o;
      return Objects.equals(name, that.name)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "SelectionVar[name=" + name + ", pos=" + pos + "]";
    }
  }

  /** {@code target.name} or {@code target?.name}. */
  final class Property implements Expr {
    private final Expr target;
    private final String name;
    private final boolean nullSafe;
    private final SourcePos pos;

    public Property(Expr target, String name, boolean nullSafe, SourcePos pos) {
      this.target = target;
      this.name = name;
      this.nullSafe = nullSafe;
      this.pos = pos;
    }

    public Expr target() {
      return target;
    }

    public String name() {
      return name;
    }

    public boolean nullSafe() {
      return nullSafe;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Property)) {
        return false;
      }
      Property that = (Property) o;
      return Objects.equals(target, that.target)
          && Objects.equals(name, that.name)
          && nullSafe == that.nullSafe
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(target);
      h = 31 * h + Objects.hashCode(name);
      h = 31 * h + Boolean.hashCode(nullSafe);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Property[target=" + target + ", name=" + name + ", nullSafe=" + nullSafe + ", pos=" + pos + "]";
    }
  }

  /** {@code target.name(args)} / {@code target?.name(args)}. Also {@code ns.fn(args)} before resolution. */
  final class MethodCall implements Expr {
    private final Expr target;
    private final String name;
    private final List<Expr> args;
    private final boolean nullSafe;
    private final SourcePos pos;

    public MethodCall(Expr target, String name, List<Expr> args, boolean nullSafe, SourcePos pos) {
      this.target = target;
      this.name = name;
      this.args = Backports.listCopy(args);
      this.nullSafe = nullSafe;
      this.pos = pos;
    }

    public Expr target() {
      return target;
    }

    public String name() {
      return name;
    }

    public List<Expr> args() {
      return args;
    }

    public boolean nullSafe() {
      return nullSafe;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof MethodCall)) {
        return false;
      }
      MethodCall that = (MethodCall) o;
      return Objects.equals(target, that.target)
          && Objects.equals(name, that.name)
          && Objects.equals(args, that.args)
          && nullSafe == that.nullSafe
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(target);
      h = 31 * h + Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(args);
      h = 31 * h + Boolean.hashCode(nullSafe);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "MethodCall[target=" + target + ", name=" + name + ", args=" + args + ", nullSafe=" + nullSafe
          + ", pos=" + pos + "]";
    }
  }

  /** {@code target[index]} on a List, array or Map. */
  final class Index implements Expr {
    private final Expr target;
    private final Expr index;
    private final SourcePos pos;

    public Index(Expr target, Expr index, SourcePos pos) {
      this.target = target;
      this.index = index;
      this.pos = pos;
    }

    public Expr target() {
      return target;
    }

    public Expr index() {
      return index;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Index)) {
        return false;
      }
      Index that = (Index) o;
      return Objects.equals(target, that.target)
          && Objects.equals(index, that.index)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(target);
      h = 31 * h + Objects.hashCode(index);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Index[target=" + target + ", index=" + index + ", pos=" + pos + "]";
    }
  }

  /** {@code #ns.fn(args)} (Thymeleaf utility-object form). */
  final class FunctionCall implements Expr {
    private final String namespace;
    private final String name;
    private final List<Expr> args;
    private final SourcePos pos;

    public FunctionCall(String namespace, String name, List<Expr> args, SourcePos pos) {
      this.namespace = namespace;
      this.name = name;
      this.args = Backports.listCopy(args);
      this.pos = pos;
    }

    public String namespace() {
      return namespace;
    }

    public String name() {
      return name;
    }

    public List<Expr> args() {
      return args;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FunctionCall)) {
        return false;
      }
      FunctionCall that = (FunctionCall) o;
      return Objects.equals(namespace, that.namespace)
          && Objects.equals(name, that.name)
          && Objects.equals(args, that.args)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(namespace);
      h = 31 * h + Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(args);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "FunctionCall[namespace=" + namespace + ", name=" + name + ", args=" + args
          + ", pos=" + pos + "]";
    }
  }

  enum UnaryOp {
    NOT,
    NEGATE
  }

  /** {@code !x}, {@code not x}, {@code -x}. */
  final class Unary implements Expr {
    private final UnaryOp op;
    private final Expr operand;
    private final SourcePos pos;

    public Unary(UnaryOp op, Expr operand, SourcePos pos) {
      this.op = op;
      this.operand = operand;
      this.pos = pos;
    }

    public UnaryOp op() {
      return op;
    }

    public Expr operand() {
      return operand;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Unary)) {
        return false;
      }
      Unary that = (Unary) o;
      return Objects.equals(op, that.op)
          && Objects.equals(operand, that.operand)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(op);
      h = 31 * h + Objects.hashCode(operand);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Unary[op=" + op + ", operand=" + operand + ", pos=" + pos + "]";
    }
  }

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
  final class Binary implements Expr {
    private final BinaryOp op;
    private final Expr left;
    private final Expr right;
    private final SourcePos pos;

    public Binary(BinaryOp op, Expr left, Expr right, SourcePos pos) {
      this.op = op;
      this.left = left;
      this.right = right;
      this.pos = pos;
    }

    public BinaryOp op() {
      return op;
    }

    public Expr left() {
      return left;
    }

    public Expr right() {
      return right;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Binary)) {
        return false;
      }
      Binary that = (Binary) o;
      return Objects.equals(op, that.op)
          && Objects.equals(left, that.left)
          && Objects.equals(right, that.right)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(op);
      h = 31 * h + Objects.hashCode(left);
      h = 31 * h + Objects.hashCode(right);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Binary[op=" + op + ", left=" + left + ", right=" + right + ", pos=" + pos + "]";
    }
  }

  /** {@code cond ? then : otherwise}; {@code otherwise} is null for {@code cond ? then}. */
  final class Conditional implements Expr {
    private final Expr condition;
    private final Expr then;
    private final Expr otherwise;
    private final SourcePos pos;

    public Conditional(Expr condition, Expr then, Expr otherwise, SourcePos pos) {
      this.condition = condition;
      this.then = then;
      this.otherwise = otherwise;
      this.pos = pos;
    }

    public Expr condition() {
      return condition;
    }

    public Expr then() {
      return then;
    }

    public Expr otherwise() {
      return otherwise;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Conditional)) {
        return false;
      }
      Conditional that = (Conditional) o;
      return Objects.equals(condition, that.condition)
          && Objects.equals(then, that.then)
          && Objects.equals(otherwise, that.otherwise)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(condition);
      h = 31 * h + Objects.hashCode(then);
      h = 31 * h + Objects.hashCode(otherwise);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Conditional[condition=" + condition + ", then=" + then + ", otherwise=" + otherwise
          + ", pos=" + pos + "]";
    }
  }

  /** {@code value ?: fallback}. */
  final class Elvis implements Expr {
    private final Expr value;
    private final Expr fallback;
    private final SourcePos pos;

    public Elvis(Expr value, Expr fallback, SourcePos pos) {
      this.value = value;
      this.fallback = fallback;
      this.pos = pos;
    }

    public Expr value() {
      return value;
    }

    public Expr fallback() {
      return fallback;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Elvis)) {
        return false;
      }
      Elvis that = (Elvis) o;
      return Objects.equals(value, that.value)
          && Objects.equals(fallback, that.fallback)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(value);
      h = 31 * h + Objects.hashCode(fallback);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Elvis[value=" + value + ", fallback=" + fallback + ", pos=" + pos + "]";
    }
  }

  /** {@code #{key(args)}}. The key is always a literal. */
  final class Message implements Expr {
    private final String key;
    private final List<Expr> args;
    private final SourcePos pos;

    public Message(String key, List<Expr> args, SourcePos pos) {
      this.key = key;
      this.args = Backports.listCopy(args);
      this.pos = pos;
    }

    public String key() {
      return key;
    }

    public List<Expr> args() {
      return args;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Message)) {
        return false;
      }
      Message that = (Message) o;
      return Objects.equals(key, that.key)
          && Objects.equals(args, that.args)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(key);
      h = 31 * h + Objects.hashCode(args);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Message[key=" + key + ", args=" + args + ", pos=" + pos + "]";
    }
  }

  /** {@code @{/path/{id}(id=x, q=y)}}. The path is always a literal. */
  final class Link implements Expr {
    private final String path;
    private final List<Assignment> params;
    private final SourcePos pos;

    public Link(String path, List<Assignment> params, SourcePos pos) {
      this.path = path;
      this.params = Backports.listCopy(params);
      this.pos = pos;
    }

    public String path() {
      return path;
    }

    public List<Assignment> params() {
      return params;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Link)) {
        return false;
      }
      Link that = (Link) o;
      return Objects.equals(path, that.path)
          && Objects.equals(params, that.params)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(path);
      h = 31 * h + Objects.hashCode(params);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Link[path=" + path + ", params=" + params + ", pos=" + pos + "]";
    }
  }

  /** {@code |Hello ${name}!|}: parts are String Literals and expressions, in order. */
  final class Interpolation implements Expr {
    private final List<Expr> parts;
    private final SourcePos pos;

    public Interpolation(List<Expr> parts, SourcePos pos) {
      this.parts = Backports.listCopy(parts);
      this.pos = pos;
    }

    public List<Expr> parts() {
      return parts;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Interpolation)) {
        return false;
      }
      Interpolation that = (Interpolation) o;
      return Objects.equals(parts, that.parts)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(parts);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Interpolation[parts=" + parts + ", pos=" + pos + "]";
    }
  }

  /** {@code ~{template :: name(args)}} used as a value (a fragment argument). */
  final class FragmentExpr implements Expr {
    private final FragmentRef ref;
    private final SourcePos pos;

    public FragmentExpr(FragmentRef ref, SourcePos pos) {
      this.ref = ref;
      this.pos = pos;
    }

    public FragmentRef ref() {
      return ref;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FragmentExpr)) {
        return false;
      }
      FragmentExpr that = (FragmentExpr) o;
      return Objects.equals(ref, that.ref)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(ref);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "FragmentExpr[ref=" + ref + ", pos=" + pos + "]";
    }
  }
}
