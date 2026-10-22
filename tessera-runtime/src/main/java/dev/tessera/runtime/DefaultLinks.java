package dev.tessera.runtime;

/** {@link Links} implementing the documented expansion rules for a fixed context path. */
public final class DefaultLinks implements Links {
  private final String contextPath;

  /** @param contextPath servlet context path, {@code ""} for root; must not end with {@code /} */
  public DefaultLinks(String contextPath) {
    this.contextPath = contextPath;
  }

  public String contextPath() {
    return contextPath;
  }

  @Override
  public String build(String path, String[] names, Object[] values) {
    throw new UnsupportedOperationException("TODO(#6): DefaultLinks.build");
  }
}
