package dev.chainguard.thwart.compiler.expr;

import dev.chainguard.thwart.compiler.SourcePos;
import java.util.Objects;

/** {@code t:each="var[, statVar] : iterable"}. {@code statVar} is null when omitted (then {@code varStat} is implied). */
public final class EachSpec {
  private final String var;
  private final String statVar;
  private final Expr iterable;
  private final SourcePos pos;

  public EachSpec(String var, String statVar, Expr iterable, SourcePos pos) {
    this.var = var;
    this.statVar = statVar;
    this.iterable = iterable;
    this.pos = pos;
  }

  public String var() {
    return var;
  }

  public String statVar() {
    return statVar;
  }

  public Expr iterable() {
    return iterable;
  }

  public SourcePos pos() {
    return pos;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof EachSpec)) {
      return false;
    }
    EachSpec that = (EachSpec) o;
    return Objects.equals(var, that.var)
        && Objects.equals(statVar, that.statVar)
        && Objects.equals(iterable, that.iterable)
        && Objects.equals(pos, that.pos);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(var);
    h = 31 * h + Objects.hashCode(statVar);
    h = 31 * h + Objects.hashCode(iterable);
    h = 31 * h + Objects.hashCode(pos);
    return h;
  }

  @Override
  public String toString() {
    return "EachSpec[var=" + var + ", statVar=" + statVar + ", iterable=" + iterable + ", pos=" + pos + "]";
  }
}
