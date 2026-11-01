package dev.tessera.compiler.types;

import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable lexical scope: the variables an expression may see (and nothing else: principle 2)
 * plus the current {@code t:object} selection. Inner declarations shadow outer ones.
 */
public final class Scope {
  private static final Scope EMPTY = new Scope(Collections.emptyMap(), null, Collections.emptyMap());

  private final Map<String, Variable> vars;
  private final Selection selection;
  /** By Java name (fresh for each local, so never shadowed): {@link #withFragment}. */
  private final Map<String, Skeleton.Node> fragments;

  private Scope(Map<String, Variable> vars, Selection selection, Map<String, Skeleton.Node> fragments) {
    this.vars = vars;
    this.selection = selection;
    this.fragments = fragments;
  }

  public static Scope empty() {
    return EMPTY;
  }

  /** The {@code t:object} target for {@code *{...}} and {@code t:field}. */
  public static final class Selection {
    private final String objectName;
    private final String code;
    private final TypeMirror type;

    public Selection(String objectName, String code, TypeMirror type) {
      this.objectName = objectName;
      this.code = code;
      this.type = type;
    }

    public String objectName() {
      return objectName;
    }

    public String code() {
      return code;
    }

    public TypeMirror type() {
      return type;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Selection)) {
        return false;
      }
      Selection that = (Selection) o;
      return Objects.equals(objectName, that.objectName)
          && Objects.equals(code, that.code)
          && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(objectName);
      h = 31 * h + Objects.hashCode(code);
      h = 31 * h + Objects.hashCode(type);
      return h;
    }

    @Override
    public String toString() {
      return "Selection[objectName=" + objectName + ", code=" + code + ", type=" + type + "]";
    }
  }

  public Scope with(Variable v) {
    Map<String, Variable> copy = new LinkedHashMap<>(vars);
    copy.remove(v.name());
    copy.put(v.name(), v);
    return new Scope(copy, selection, fragments);
  }

  public Scope withSelection(Selection s) {
    return new Scope(vars, s, fragments);
  }

  /**
   * This scope, recording that the {@code Fragment} local with Java name {@code javaName} (a
   * {@code t:with} alias) writes one of {@code candidates}, the skeleton the verifier checks where
   * it is inserted (#561).
   */
  public Scope withFragment(String javaName, Skeleton.Node candidates) {
    Map<String, Skeleton.Node> copy = new LinkedHashMap<>(fragments);
    copy.put(javaName, candidates);
    return new Scope(vars, selection, copy);
  }

  /** What the {@code Fragment} local with Java name {@code javaName} writes, if recorded ({@link #withFragment}). */
  public Optional<Skeleton.Node> fragment(String javaName) {
    return Optional.ofNullable(fragments.get(javaName));
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
