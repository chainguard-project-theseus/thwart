package dev.tessera.compiler.verify.engine;

import dev.tessera.compiler.internal.html5.current.impl.TesseraAccess;

/** The current upstream htmlparser (customizable {@code <select>}), from validator 26.10.12. */
final class VnuEngine implements Engine {
  static final String NAME = "htmlparser@validator-26.10.12";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Parser document(boolean scripting) throws EngineException {
    try {
      return new VnuParser(this, new Raw(TesseraAccess.document(scripting)));
    } catch (Exception | AssertionError e) {
      throw VnuParser.failure(this, "starting a document parser", e);
    }
  }

  @Override
  public Parser fragment(String contextNamespace, String contextName, boolean scripting)
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
          new Raw(TesseraAccess.fragment(contextNamespace, contextName, scripting)));
    } catch (Exception | AssertionError e) {
      throw VnuParser.failure(this, "starting a fragment parser in <" + contextName + ">", e);
    }
  }

  @Override
  public String toString() {
    return NAME;
  }

  /** {@link RawParser} over the shaded build's {@code TesseraAccess}. */
  private static final class Raw implements RawParser {
    private final TesseraAccess a;

    Raw(TesseraAccess a) {
      this.a = a;
    }

    @Override
    public void feed(char[] chars, int start, int end, final RawListener listener) throws Exception {
      a.feed(chars, start, end, listener == null ? null : new TesseraAccess.Listener() {
        @Override
        public void error(String message, int line, int column) {
          listener.error(message, line, column);
        }

        @Override
        public void pushed(String namespace, String localName, boolean onStack, int line, int column) {
          listener.pushed(namespace, localName, onStack, line, column);
        }

        @Override
        public void cdata(String namespace, String localName, boolean htmlIntegrationPoint, int line, int column) {
          listener.cdata(namespace, localName, htmlIntegrationPoint, line, column);
        }
      });
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
    public RawTree tree() throws Exception {
      TesseraAccess.Tree t = a.tree();
      RawTree r = new RawTree();
      r.stackNamespaces = t.stackNamespaces;
      r.stackNames = t.stackNames;
      r.stackHtmlIntegrationPoints = t.stackHtmlIntegrationPoints;
      r.formattingStackIndex = t.formattingStackIndex;
      r.formattingNamespaces = t.formattingNamespaces;
      r.formattingNames = t.formattingNames;
      r.templateModes = t.templateModes;
      r.mode = t.mode;
      r.originalMode = t.originalMode;
      r.formPointer = t.formPointer;
      r.headPointer = t.headPointer;
      r.quirks = t.quirks;
      r.needToDropLF = t.needToDropLF;
      r.pendingTableText = t.pendingTableText;
      return r;
    }
  }
}
