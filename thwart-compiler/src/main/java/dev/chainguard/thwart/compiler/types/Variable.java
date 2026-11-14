package dev.chainguard.thwart.compiler.types;

import java.util.Objects;
import javax.lang.model.type.TypeMirror;

/**
 * A variable visible to expressions.
 *
 * @param name template-level name ({@code [A-Za-z_][A-Za-z0-9_]*}); names containing
 *     {@code $} are internal (unreachable from templates)
 * @param type static type
 * @param javaName Java local/parameter name in generated code
 */
public final class Variable {
  private final String name;
  private final TypeMirror type;
  private final String javaName;

  public Variable(String name, TypeMirror type, String javaName) {
    this.name = name;
    this.type = type;
    this.javaName = javaName;
  }

  public String name() {
    return name;
  }

  public TypeMirror type() {
    return type;
  }

  public String javaName() {
    return javaName;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Variable)) {
      return false;
    }
    Variable that = (Variable) o;
    return Objects.equals(name, that.name)
        && Objects.equals(type, that.type)
        && Objects.equals(javaName, that.javaName);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(name);
    h = 31 * h + Objects.hashCode(type);
    h = 31 * h + Objects.hashCode(javaName);
    return h;
  }

  @Override
  public String toString() {
    return "Variable[name=" + name + ", type=" + type + ", javaName=" + javaName + "]";
  }
}
