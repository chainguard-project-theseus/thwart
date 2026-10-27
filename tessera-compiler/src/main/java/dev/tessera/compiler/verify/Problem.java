package dev.tessera.compiler.verify;

/**
 * What the context verifier rejects (#367). The names are stable rule ids: each error message
 * carries one ({@link VerifierMessages}), and #368 maps them to fix-its. Add new kinds at the end;
 * never rename or delete one: a retired id is no longer reported, and its constant stays.
 */
public enum Problem {
  /** A text or RCDATA hole where the tokenizer isn't in data, RCDATA or never-executed raw text. */
  TEXT_NOT_IN_TEXT,
  /** A text or RCDATA hole in script data, style raw text, or an SVG/MathML {@code script}/{@code style}. */
  TEXT_IN_SCRIPT,
  /** A text hole in a table mode: the parser foster-parents the value (or holds it back) out of the table. */
  TEXT_IN_TABLE,
  /** A text hole where only head content, whitespace or nothing may appear (before or in head, after body). */
  TEXT_OUTSIDE_BODY,
  /**
   * Retired (#410), no longer reported: a text hole where inserting a character would first
   * reconstruct misnested formatting elements. The misnesting is a {@link #PARSE_ERROR}.
   */
  TEXT_RECONSTRUCTS,
  /**
   * An RCDATA hole (escaped for {@code <title>}/{@code <textarea>}, without the comments that break
   * double braces) that a browser reads as ordinary text, where client frameworks look for them.
   */
  RCDATA_NOT_IN_RCDATA,
  /** An attribute hole that isn't in a double-quoted attribute value. */
  ATTRIBUTE_NOT_IN_VALUE,
  /** An attribute hole in a value of a different attribute than the compiler wrote (or a dropped duplicate). */
  ATTRIBUTE_WRONG_NAME,
  /** An attribute hole whose value decides how the rest parses (MathML {@code encoding}). */
  ATTRIBUTE_VALUE_CHANGES_PARSE,
  /** A JSON hole outside the script data of an HTML {@code <script>}. */
  JSON_NOT_IN_SCRIPT,
  /** A {@code SafeHtml}, {@code Fragment} value or HTML message outside flow content. */
  RAW_NOT_FLOW,
  /**
   * A {@code frameset} element (a coarse exclusion, {@link Rules#exclusion}): whether a {@code
   * <frameset>} start tag applies depends on the content before it.
   */
  FRAMESET,
  /**
   * Retired (#444), no longer reported: three or more identical formatting elements, one with a
   * computed attribute (the Noah's Ark clause). In an error-free parse the value can't change the
   * tree, and misnested they are a {@link #PARSE_ERROR}.
   */
  NOAHS_ARK,
  /** A loop whose parser states don't reach a fixed point. */
  LOOP_NO_FIXED_POINT,
  /** A fragment that recurses into states that don't reach a fixed point, or reach a bad state only through recursion. */
  STRUCTURE_DEPENDS_ON_DATA,
  /** More distinct parser states at a join than the verifier tracks. */
  TOO_MANY_STATES,
  /** A parser engine failed. */
  ENGINE_FAILURE,
  /** A skeleton node the verifier can't interpret (an unknown fragment). */
  UNKNOWN_NODE,
  /**
   * A {@code SafeHtml}, {@code Fragment} value or HTML message where its markup would close, reopen
   * or adopt an element open around it (an open {@code <p>}, {@code <a>}, heading, {@code <form>},
   * ...; {@link ContentKinds#closer}), so the rest of the page would parse differently.
   */
  RAW_CLOSES_ELEMENT,
  /**
   * An HTML message whose markup leaves the parser in another state than it found it: it closes an
   * element open around it (a {@code <p>} in a {@code <p>}, an {@code <a>} in an {@code <a>}), or
   * leaves one of its own open (a {@code <p>} without {@code </p>}).
   */
  MESSAGE_CHANGES_STATE,
  /**
   * The parser reports a parse error (#406): the markup parses only by error recovery (foster
   * parenting, misnested or stray tags, a second {@code <body>}, ...), where browsers may build
   * different trees. The missing-doctype messages are allowed ({@link Rules#allowedError}).
   */
  PARSE_ERROR,
  /** An {@code svg} or {@code math} element inside a table part or {@code <template>} (#398, exclusion (a)). */
  FOREIGN_IN_TABLE,
  /** An HTML table part directly in {@code <template>} (#401, exclusion (b)). */
  TABLE_IN_TEMPLATE,
  /** An element other than {@code option}, {@code optgroup} or {@code hr} in a {@code <select>} (#305, exclusion (c)). */
  SELECT_CONTENT
}
