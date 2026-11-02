package dev.tessera.compiler.verify.engine;

import dev.tessera.compiler.internal.html5.current.impl.TesseraAccess;

/**
 * The spec HTML parser the build-time verifier runs (security-architecture.md §11.4): validator.nu
 * htmlparser from {@code nu.validator:validator:26.10.12}, built for Java 8, renamed into {@code
 * dev.tessera.compiler.internal.html5} and shaded into this jar. Its parsers report parse
 * errors and inserted elements to a {@link Parser.Listener}.
 */
public final class Vnu {
  private Vnu() {}

  /** The parser and its version, for messages. */
  public static final String NAME = "htmlparser@validator-26.10.12";

  /** Document parsing from the initial insertion mode. */
  public static Parser document(boolean scripting) throws EngineException {
    try {
      return new VnuParser(new Raw(TesseraAccess.document(scripting)));
    } catch (Exception | AssertionError e) {
      throw VnuParser.failure("starting a document parser", e);
    }
  }

  /**
   * Fragment parsing (as for {@code innerHTML}) in the context of an element.
   *
   * @param contextNamespace the HTML, SVG or MathML namespace URI
   * @param contextName the context element's local name, lower case
   */
  public static Parser fragment(String contextNamespace, String contextName, boolean scripting)
      throws EngineException {
    if (!OpenElement.HTML.equals(contextNamespace) && !OpenElement.SVG.equals(contextNamespace)
        && !OpenElement.MATHML.equals(contextNamespace)) {
      throw new IllegalArgumentException("context namespace must be HTML, SVG or MathML: " + contextNamespace);
    }
    if (contextName == null || contextName.isEmpty()) {
      throw new IllegalArgumentException("context element name is empty");
    }
    try {
      return new VnuParser(new OpenElement(contextNamespace, contextName, false),
          new Raw(TesseraAccess.fragment(contextNamespace, contextName, scripting)));
    } catch (Exception | AssertionError e) {
      throw VnuParser.failure("starting a fragment parser in <" + contextName + ">", e);
    }
  }

  /** {@link RawParser} over the shaded build's {@code TesseraAccess}. */
  private static final class Raw implements RawParser {
    private final TesseraAccess a;

    Raw(TesseraAccess a) {
      this.a = a;
    }

    @Override
    public void feed(char[] chars, int start, int end, TesseraAccess.Listener listener) throws Exception {
      a.feed(chars, start, end, listener);
    }

    @Override
    public int stackLength() {
      return a.stackLength();
    }

    @Override
    public String stackNamespace(int i) {
      return a.stackNamespace(i);
    }

    @Override
    public String stackName(int i) {
      return a.stackName(i);
    }

    @Override
    public int templateModeCount() {
      return a.templateModeCount();
    }

    @Override
    public String templateMode(int i) {
      return a.templateMode(i);
    }

    @Override
    public RawParser fork() throws Exception {
      return new Raw(a.fork());
    }

    @Override
    public boolean scripting() {
      return a.scripting();
    }

    @Override
    public String tokenizerState() {
      return a.tokenizerState();
    }

    @Override
    public String attributeName() {
      return a.attributeName();
    }

    @Override
    public boolean betweenTokens() {
      return a.betweenTokens();
    }

    @Override
    public String endTagExpectation() {
      return a.endTagExpectation();
    }

    @Override
    public boolean lastCR() {
      return a.lastCR();
    }

    @Override
    public TesseraAccess.Tree tree() throws Exception {
      return a.tree();
    }
  }
}
