package dev.tessera.compiler.verify.engine;

/**
 * What {@link VnuParser} needs from one engine build: a thin view of that build's
 * {@code TesseraAccess}, in plain types. One adapter per build ({@link CurrentEngine},
 * {@link ClassicEngine}); they differ only in the package they import.
 */
interface RawParser {
  void feed(char[] chars, int start, int end) throws Exception;

  RawParser fork() throws Exception;

  boolean scripting();

  /** A name from {@link TokenizerState}, or null. */
  String tokenizerState();

  String attributeName();

  boolean betweenTokens();

  String endTagExpectation();

  boolean lastCR();

  RawTree tree() throws Exception;

  /** The tree builder's state, as {@code TesseraAccess.Tree} reports it. */
  final class RawTree {
    String[] stackNamespaces;
    String[] stackNames;
    boolean[] stackHtmlIntegrationPoints;
    int[] formattingStackIndex;
    String[] formattingNamespaces;
    String[] formattingNames;
    String[][] formattingAttributes;
    String[] templateModes;
    String mode;
    String originalMode;
    boolean framesetOk;
    boolean formPointer;
    boolean headPointer;
    boolean quirks;
    boolean needToDropLF;
    boolean pendingTableText;
  }
}
