package dev.chainguard.thwart.runtime;

/** A model attribute had the wrong type for the template parameter of the same name. */
public final class TemplateModelException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public TemplateModelException(String message) {
    super(message);
  }
}
