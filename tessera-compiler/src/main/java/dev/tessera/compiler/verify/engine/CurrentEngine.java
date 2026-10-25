package dev.tessera.compiler.verify.engine;

import dev.tessera.compiler.internal.html5.current.impl.TesseraAccess;

/** The current upstream htmlparser (customizable {@code <select>}), from validator 26.10.12. */
final class CurrentEngine extends VnuEngine {
  static final String NAME = "htmlparser@validator-26.10.12";

  CurrentEngine() {
    super(NAME);
  }

  @Override
  RawParser rawDocument(boolean scripting) throws Exception {
    return new Raw(TesseraAccess.document(scripting));
  }

  @Override
  RawParser rawFragment(String namespace, String name, boolean scripting) throws Exception {
    return new Raw(TesseraAccess.fragment(namespace, name, scripting));
  }

  private static final class Raw implements RawParser {
    private final TesseraAccess a;

    Raw(TesseraAccess a) {
      this.a = a;
    }

    @Override
    public void feed(char[] chars, int start, int end) throws Exception {
      a.feed(chars, start, end);
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
      r.formattingAttributes = t.formattingAttributes;
      r.templateModes = t.templateModes;
      r.mode = t.mode;
      r.originalMode = t.originalMode;
      r.framesetOk = t.framesetOk;
      r.formPointer = t.formPointer;
      r.headPointer = t.headPointer;
      r.quirks = t.quirks;
      r.needToDropLF = t.needToDropLF;
      r.pendingTableText = t.pendingTableText;
      return r;
    }
  }
}
