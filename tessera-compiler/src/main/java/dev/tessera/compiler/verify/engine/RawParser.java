package dev.tessera.compiler.verify.engine;

/**
 * What {@link VnuParser} needs from one engine build: a thin view of that build's
 * {@code TesseraAccess}, in plain types. One adapter per build ({@link CurrentEngine},
 * {@link ClassicEngine}); they differ only in the package they import.
 */
interface RawParser {
  void feed(char[] chars, int start, int end) throws Exception;

  /**
   * {@link #feed(char[], int, int)}, telling {@code listener} about parse errors and inserted
   * elements (#406). A build that doesn't report them ignores the listener.
   */
  default void feed(char[] chars, int start, int end, RawListener listener) throws Exception {
    feed(chars, start, end);
  }

  /** The number of open elements, also while a {@link RawListener} is called. */
  default int stackLength() {
    throw new UnsupportedOperationException();
  }

  /** The namespace URI of open element {@code i}, from 0 (the root). */
  default String stackNamespace(int i) {
    throw new UnsupportedOperationException();
  }

  /** The local name of open element {@code i}, from 0 (the root). */
  default String stackName(int i) {
    throw new UnsupportedOperationException();
  }

  /** The number of template insertion modes. */
  default int templateModeCount() {
    throw new UnsupportedOperationException();
  }

  /** Template insertion mode {@code i} (a name from {@link InsertionMode}), from 0 (the outermost). */
  default String templateMode(int i) {
    throw new UnsupportedOperationException();
  }

  /**
   * What a build reports while it parses, at the tokenizer's line (from 1) and column (from 1) in
   * the input fed to this raw parser since it was created (a fork counts afresh).
   */
  interface RawListener {
    void error(String message, int line, int column);

    /** An element inserted; {@code onStack}: pushed, not a void element (already popped). */
    void pushed(String namespace, String localName, boolean onStack, int line, int column);
  }

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
