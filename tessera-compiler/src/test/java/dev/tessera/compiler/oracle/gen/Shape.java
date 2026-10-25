package dev.tessera.compiler.oracle.gen;

import dev.tessera.compiler.oracle.gen.Container.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A point in the generator's grammar (#321): one value per axis. {@link #template()} writes it as a
 * complete template, {@code <div t:params="...">...</div>}.
 *
 * @param outer the outermost wrapper (one of {@link Container#WRAPPERS})
 * @param middle the wrapper inside it (one of {@link Container#WRAPPERS})
 * @param inner the innermost container, which holds the hole
 * @param interference static markup right before (and for some values after) the hole
 * @param hole where the value goes
 * @param composition how the hole reaches the page
 * @param insertion the container the fragment is inserted under; non-null exactly when {@code
 *     composition.inserts()}
 */
public record Shape(
    Container outer,
    Container middle,
    Container inner,
    Interference interference,
    Hole hole,
    Composition composition,
    Container insertion) {

  /** A template parameter: {@code String s}, {@code SafeHtml h} or {@code Fragment f}. */
  public record Param(String type, String name) {
    @Override
    public String toString() {
      return type + " " + name;
    }
  }

  public static final Param STRING = new Param("String", "s");
  public static final Param SAFE_HTML = new Param("SafeHtml", "h");
  public static final Param FRAGMENT = new Param("Fragment", "f");

  public Shape {
    Objects.requireNonNull(outer, "outer");
    Objects.requireNonNull(middle, "middle");
    Objects.requireNonNull(inner, "inner");
    Objects.requireNonNull(interference, "interference");
    Objects.requireNonNull(hole, "hole");
    Objects.requireNonNull(composition, "composition");
    if (composition.inserts() != (insertion != null)) {
      throw new IllegalArgumentException("an insertion point goes with t:insert/t:replace only: " + composition);
    }
  }

  /** A depth-1 (or depth-0, with {@link Container#NONE}) shape with no composition. */
  public static Shape of(Container inner, Interference interference, Hole hole) {
    return new Shape(Container.NONE, Container.NONE, inner, interference, hole, Composition.NONE, null);
  }

  /** This shape with another composition (and insertion point, for insert/replace). */
  public Shape with(Composition composition, Container insertion) {
    return new Shape(outer, middle, inner, interference, hole, composition, insertion);
  }

  /** This shape with other wrappers. */
  public Shape wrappedIn(Container outer, Container middle) {
    return new Shape(outer, middle, inner, interference, hole, composition, insertion);
  }

  /** This shape with other static interference. */
  public Shape with(Interference interference) {
    return new Shape(outer, middle, inner, interference, hole, composition, insertion);
  }

  /** The template's parameters, in declaration order. */
  public List<Param> params() {
    List<Param> params = new ArrayList<>();
    params.add(hole.safeHtml() ? SAFE_HTML : STRING);
    if (composition == Composition.FRAGMENT_PARAM) {
      params.add(FRAGMENT);
    }
    return List.copyOf(params);
  }

  /** The containers from the outside in, without {@link Container#NONE}. */
  public List<Container> chain() {
    List<Container> chain = new ArrayList<>();
    for (Container c : List.of(outer, middle, inner)) {
      if (c != Container.NONE) {
        chain.add(c);
      }
    }
    return chain;
  }

  /** A stable, human-readable name: the axis values. */
  public String name() {
    StringBuilder sb = new StringBuilder("gen ");
    List<Container> chain = chain();
    if (chain.isEmpty()) {
      sb.append("none");
    }
    for (int i = 0; i < chain.size(); i++) {
      sb.append(i == 0 ? "" : " ").append(chain.get(i).label());
    }
    sb.append(" | ").append(interference.label()).append(" | ").append(hole.label()).append(" | ").append(composition.label());
    if (insertion != null) {
      sb.append(" into ").append(insertion.label());
    }
    return sb.toString();
  }

  /** The complete template: {@code <div t:params="...">}, the body, {@code </div>}. */
  public String template() {
    StringBuilder out = new StringBuilder("<div t:params=\"");
    List<Param> params = params();
    for (int i = 0; i < params.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(params.get(i));
    }
    out.append("\">");
    body(out);
    return out.append("</div>").toString();
  }

  private void body(StringBuilder out) {
    List<Element> chain = new ArrayList<>();
    for (Container c : chain()) {
      chain.addAll(c.elements());
    }
    boolean needsHost =
        hole.kind() == Hole.Kind.ON_CONTAINER || composition.inserts() || composition == Composition.REMOVE_TAG;
    if (chain.isEmpty() && needsHost) {
      chain.add(new Element("span", ""));
    }
    String value = hole.safeHtml() ? "h" : "s";
    StringBuilder hostAttributes = new StringBuilder();
    if (composition.inserts()) {
      hostAttributes.append(" t:fragment=\"frag(").append(params().get(0)).append(")\"");
    } else if (composition == Composition.REMOVE_TAG) {
      hostAttributes.append(" t:remove=\"tag\"");
    }
    if (hole.kind() == Hole.Kind.ON_CONTAINER) {
      hostAttributes.append(hole.markup());
    }
    String fragmentSlot = composition == Composition.FRAGMENT_PARAM ? "<t:block t:replace=\"f\"></t:block>" : "";

    int last = chain.size() - 1;
    for (int i = 0; i < last; i++) {
      open(out, chain.get(i), "");
    }
    if (hole.kind() == Hole.Kind.ON_CONTAINER) {
      out.append(interference.prefix());
      open(out, chain.get(last), hostAttributes.toString());
      close(out, chain.get(last));
      out.append(interference.suffix()).append(fragmentSlot);
    } else {
      if (last >= 0) {
        open(out, chain.get(last), hostAttributes.toString());
      }
      out.append(holeBody(last >= 0 ? chain.get(last).name() : "div")).append(fragmentSlot);
      if (last >= 0) {
        close(out, chain.get(last));
      }
    }
    for (int i = last - 1; i >= 0; i--) {
      close(out, chain.get(i));
    }

    if (composition.inserts()) {
      List<Element> point = insertion.elements();
      String call = "~{:: frag(" + value + ")}";
      for (int i = 0; i < point.size(); i++) {
        boolean innermost = i == point.size() - 1;
        open(out, point.get(i), innermost && composition == Composition.INSERT ? " t:insert=\"" + call + "\"" : "");
      }
      if (composition == Composition.REPLACE) {
        out.append("<t:block t:replace=\"").append(call).append("\"></t:block>");
      }
      for (int i = point.size() - 1; i >= 0; i--) {
        close(out, point.get(i));
      }
    }
  }

  private String holeBody(String innermost) {
    String prefix = interference.prefix();
    String suffix = interference.suffix();
    return switch (hole.kind()) {
      case INLINE, CARRIER -> prefix + hole.markup() + suffix;
      case SCRIPT -> code("script", innermost, prefix, suffix);
      case STYLE -> code("style", innermost, prefix, suffix);
      case ON_CONTAINER -> throw new IllegalStateException("placed on the container");
    };
  }

  private String code(String element, String innermost, String prefix, String suffix) {
    String code = hole.markup() + prefix + "[[s]]" + suffix + hole.after();
    return innermost.equals(element) ? code : "<" + element + ">" + code + "</" + element + ">";
  }

  private static void open(StringBuilder out, Element e, String extra) {
    out.append('<').append(e.name()).append(e.attributes()).append(extra).append('>');
  }

  private static void close(StringBuilder out, Element e) {
    out.append("</").append(e.name()).append('>');
  }
}
