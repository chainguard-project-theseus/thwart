package dev.tessera.compiler.expr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import java.util.List;
import java.util.Objects;

/**
 * {@code ~{template :: selector(args)}} (the {@code ~{}} wrapper is optional in
 * {@code t:insert}/{@code t:replace}).
 *
 * @param template view name of another template, or null for {@code ~{:: x}} / {@code ~{this :: x}}
 * @param selector fragment name, or an element name when used as a Fragment value ({@code ~{::body}})
 * @param args arguments; all named or all positional
 */
public final class FragmentRef {
  private final String template;
  private final String selector;
  private final List<Arg> args;
  private final SourcePos pos;

  public FragmentRef(String template, String selector, List<Arg> args, SourcePos pos) {
    this.template = template;
    this.selector = selector;
    this.args = Backports.listCopy(args);
    this.pos = pos;
  }

  public String template() {
    return template;
  }

  public String selector() {
    return selector;
  }

  public List<Arg> args() {
    return args;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FragmentRef)) {
      return false;
    }
    FragmentRef that = (FragmentRef) o;
    return Objects.equals(template, that.template)
        && Objects.equals(selector, that.selector)
        && Objects.equals(args, that.args)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(template);
    h = 31 * h + Objects.hashCode(selector);
    h = 31 * h + Objects.hashCode(args);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "FragmentRef[template=" + template + ", selector=" + selector + ", args=" + args
        + ", pos=" + pos + "]";
  }

  /** {@code name} is null for positional arguments. */
  public static final class Arg {
    private final String name;
    private final Expr value;
    private final SourcePos pos;

    public Arg(String name, Expr value, SourcePos pos) {
      this.name = name;
      this.value = value;
      this.pos = pos;
    }

    public String name() {
      return name;
    }

    public Expr value() {
      return value;
    }

    public SourcePos pos() {
      return pos;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Arg)) {
        return false;
      }
      Arg that = (Arg) o;
      return Objects.equals(name, that.name)
          && Objects.equals(value, that.value)
          && Objects.equals(pos, that.pos);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(value);
      h = 31 * h + Objects.hashCode(pos);
      return h;
    }

    @Override
    public String toString() {
      return "Arg[name=" + name + ", value=" + value + ", pos=" + pos + "]";
    }
  }
}
