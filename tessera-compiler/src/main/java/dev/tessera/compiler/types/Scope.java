package dev.tessera.compiler.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable lexical scope: the variables an expression may see (and nothing else: principle 2)
 * plus the current {@code t:object} selection. Inner declarations shadow outer ones.
 */
public final class Scope {
  private static final Scope EMPTY = new Scope(Map.of(), null);

  private final Map<String, Variable> vars;
  private final Selection selection;

  private Scope(Map<String, Variable> vars, Selection selection) {
    this.vars = vars;
    this.selection = selection;
  }

  public static Scope empty() {
    return EMPTY;
  }

  /** The {@code t:object} target for {@code *{...}} and {@code t:field}. */
  public record Selection(String objectName, String code, TypeMirror type) {}

  public Scope with(Variable v) {
    Map<String, Variable> copy = new LinkedHashMap<>(vars);
    copy.remove(v.name());
    copy.put(v.name(), v);
    return new Scope(copy, selection);
  }

  public Scope withSelection(Selection s) {
    return new Scope(vars, s);
  }

  public Optional<Variable> lookup(String name) {
    return Optional.ofNullable(vars.get(name));
  }

  public Optional<Selection> selection() {
    return Optional.ofNullable(selection);
  }

  /** Visible variables, outermost first (used to pass the scope to generated helper methods). */
  public List<Variable> variables() {
    return new ArrayList<>(vars.values());
  }
}
