package dev.tessera.compiler.verify.engine;

/**
 * What {@link VnuParser} needs from the engine build: a thin view of its {@code TesseraAccess}, in
 * plain types ({@link VnuEngine}'s adapter; tests substitute failing ones).
 */
interface RawParser {
  /**
   * Tokenizes {@code chars[start, end)}, telling {@code listener} (if not null) about parse errors
   * and inserted elements (#406).
   */
  void feed(char[] chars, int start, int end, RawListener listener) throws Exception;

  /** The number of open elements, also while a {@link RawListener} is called. */
  int stackLength();

  /** The namespace URI of open element {@code i}, from 0 (the root). */
  String stackNamespace(int i);

  /** The local name of open element {@code i}, from 0 (the root). */
  String stackName(int i);

  /** The number of template insertion modes. */
  int templateModeCount();

  /** Template insertion mode {@code i} (a name from {@link InsertionMode}), from 0 (the outermost). */
  String templateMode(int i);

  /**
   * What the build reports while it parses, at the tokenizer's line (from 1) and column (from 1) in
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
    String[] templateModes;
    String mode;
    String originalMode;
    boolean formPointer;
    boolean headPointer;
    boolean quirks;
    boolean needToDropLF;
    boolean pendingTableText;
  }
}
