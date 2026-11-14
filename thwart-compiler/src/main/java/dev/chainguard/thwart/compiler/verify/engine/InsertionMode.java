package dev.chainguard.thwart.compiler.verify.engine;

/**
 * The tree builder's insertion mode, engine-independent (WHATWG names). validator.nu has no
 * "in table text" mode: it holds table text back instead ({@link ParserState#pendingTableText()}).
 * There are no select modes either: the engine parses {@code <select>} the customizable way.
 */
public enum InsertionMode {
  INITIAL,
  BEFORE_HTML,
  BEFORE_HEAD,
  IN_HEAD,
  IN_HEAD_NOSCRIPT,
  AFTER_HEAD,
  IN_BODY,
  TEXT,
  IN_TABLE,
  IN_CAPTION,
  IN_COLUMN_GROUP,
  IN_TABLE_BODY,
  IN_ROW,
  IN_CELL,
  IN_TEMPLATE,
  AFTER_BODY,
  IN_FRAMESET,
  AFTER_FRAMESET,
  AFTER_AFTER_BODY,
  AFTER_AFTER_FRAMESET
}
