package dev.tessera.compiler.verify.engine;

import dev.tessera.compiler.internal.html5.current.impl.TesseraAccess;

/**
 * What {@link VnuParser} needs from the engine build: its {@code TesseraAccess} ({@link Vnu}'s
 * adapter; tests substitute failing ones).
 */
interface RawParser {
  /**
   * Tokenizes {@code chars[start, end)}, telling {@code listener} (if not null) about parse errors,
   * inserted elements and CDATA sections, at the tokenizer's line and column (from 1) in the
   * input fed to this raw parser since it was created (a fork counts afresh).
   */
  void feed(char[] chars, int start, int end, TesseraAccess.Listener listener) throws Exception;

  /** The number of open elements, also while a listener is called. */
  int stackLength();

  /** The namespace URI of open element {@code i}, from 0 (the root). */
  String stackNamespace(int i);

  /** The local name of open element {@code i}, from 0 (the root). */
  String stackName(int i);

  /** The number of template insertion modes. */
  int templateModeCount();

  /** Template insertion mode {@code i} (a name from {@link InsertionMode}), from 0 (the outermost). */
  String templateMode(int i);

  RawParser fork() throws Exception;

  boolean scripting();

  /** A name from {@link TokenizerState}, or null. */
  String tokenizerState();

  String attributeName();

  boolean betweenTokens();

  String endTagExpectation();

  boolean lastCR();

  TesseraAccess.Tree tree() throws Exception;
}
