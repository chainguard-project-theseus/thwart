package dev.tessera.compiler.verify.engine;

/** An element on the stack of open elements, or an entry in the list of active formatting elements. */
public final class OpenElement {
  public static final String HTML = "http://www.w3.org/1999/xhtml";
  public static final String SVG = "http://www.w3.org/2000/svg";
  public static final String MATHML = "http://www.w3.org/1998/Math/MathML";

  private final String namespace;
  private final String localName;
  private final boolean htmlIntegrationPoint;

  public OpenElement(String namespace, String localName, boolean htmlIntegrationPoint) {
    this.namespace = namespace;
    this.localName = localName;
    this.htmlIntegrationPoint = htmlIntegrationPoint;
  }

  /** The namespace URI: {@link #HTML}, {@link #SVG} or {@link #MATHML}. */
  public String namespace() {
    return namespace;
  }

  public String localName() {
    return localName;
  }

  /**
   * An HTML integration point (SVG {@code foreignObject}, {@code desc}, {@code title}, or MathML
   * {@code annotation-xml} with an HTML {@code encoding}). The flag is fixed when the
   * element is created from its attributes, so it is part of the state.
   */
  public boolean htmlIntegrationPoint() {
    return htmlIntegrationPoint;
  }

  public boolean isHtml(String name) {
    return HTML.equals(namespace) && localName.equals(name);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OpenElement)) {
      return false;
    }
    OpenElement e = (OpenElement) o;
    return namespace.equals(e.namespace) && localName.equals(e.localName)
        && htmlIntegrationPoint == e.htmlIntegrationPoint;
  }

  @Override
  public int hashCode() {
    return (namespace.hashCode() * 31 + localName.hashCode()) * 2 + (htmlIntegrationPoint ? 1 : 0);
  }

  /** {@code div}, {@code svg:svg}, {@code math:annotation-xml+hip}. */
  @Override
  public String toString() {
    String prefix = HTML.equals(namespace) ? "" : SVG.equals(namespace) ? "svg:" : MATHML.equals(namespace) ? "math:" : "{" + namespace + "}";
    return prefix + localName + (htmlIntegrationPoint ? "+hip" : "");
  }
}
