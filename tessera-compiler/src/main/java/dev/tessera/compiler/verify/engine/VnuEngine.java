package dev.tessera.compiler.verify.engine;

/** An {@link Engine} over one validator.nu htmlparser build. */
abstract class VnuEngine implements Engine {
  static {
    // The parsers' own Java asserts are developer checks that vnu ships disabled, and some are
    // wrong: under -ea, htmlparser 1.4.16 throws "strBufLen not reset after previous use!" on
    // "&drop</s" (an unmatched named character reference leaves its buffer dirty; the next line
    // resets it, so the parse is right), and its "assert fragment" checks fire on template
    // contents in documents. Run them as shipped, with or without -ea (javac -J-ea, surefire),
    // before any of their classes is initialized. Real failures still surface: an exception is an
    // EngineException, and an unknown state is too.
    ClassLoader loader = VnuEngine.class.getClassLoader();
    if (loader != null) {
      loader.setPackageAssertionStatus("dev.tessera.compiler.internal.html5", false);
    }
  }

  private final String name;

  VnuEngine(String name) {
    this.name = name;
  }

  abstract RawParser rawDocument(boolean scripting) throws Exception;

  abstract RawParser rawFragment(String namespace, String name, boolean scripting) throws Exception;

  @Override
  public final String name() {
    return name;
  }

  @Override
  public final Parser document(boolean scripting) throws EngineException {
    try {
      return new VnuParser(this, rawDocument(scripting));
    } catch (Exception | AssertionError e) {
      throw VnuParser.failure(this, "starting a document parser", e);
    }
  }

  @Override
  public final Parser fragment(String contextNamespace, String contextName, boolean scripting)
      throws EngineException {
    if (!OpenElement.HTML.equals(contextNamespace) && !OpenElement.SVG.equals(contextNamespace)
        && !OpenElement.MATHML.equals(contextNamespace)) {
      throw new IllegalArgumentException("context namespace must be HTML, SVG or MathML: " + contextNamespace);
    }
    if (contextName == null || contextName.isEmpty()) {
      throw new IllegalArgumentException("context element name is empty");
    }
    try {
      return new VnuParser(this, new OpenElement(contextNamespace, contextName, false),
          rawFragment(contextNamespace, contextName, scripting));
    } catch (Exception | AssertionError e) {
      throw VnuParser.failure(this, "starting a fragment parser in <" + contextName + ">", e);
    }
  }

  @Override
  public String toString() {
    return name;
  }
}
