package dev.tessera.compiler.verify.engine;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * An engine's parser state after some input, engine-independent and immutable.
 *
 * <p>Two states are {@link #equals equal} (same {@link #signature()}) only if the rest of any
 * input parses the same way from both: the verifier merges paths on it. Where Tessera can't read
 * a part of the state exactly, the signature keeps more than needed, so equal signatures still
 * mean equal parses. Equal parses may have different signatures; that costs the verifier a merge,
 * never soundness. States from different engines or fragment contexts are never equal. The one
 * exception is {@link #framesetOk()}, which only matters to a {@code <frameset>} start tag.
 * Inside a token (a tag, comment, doctype or character reference) the state is described by
 * {@link #pendingInput()}.
 *
 * <p>No part of it is an engine's numeric constant.
 */
public final class ParserState {

  /** {@link #activeFormatting()} entry for a marker. */
  public static final int MARKER = -1;

  /** {@link #activeFormatting()} entry for an element that isn't on the stack (reconstruction would recreate it). */
  public static final int NOT_ON_STACK = -2;

  private final String engine;
  private final OpenElement fragmentContext;
  private final boolean scripting;
  private final TokenizerState tokenizer;
  private final String attributeName;
  private final String endTagExpectation;
  private final boolean lastCR;
  private final String pendingInput;
  private final InsertionMode mode;
  private final InsertionMode originalMode;
  private final List<OpenElement> stack;
  private final List<Integer> activeFormatting;
  private final List<OpenElement> activeFormattingElements;
  private final List<InsertionMode> templateModes;
  private final boolean formPointer;
  private final boolean headPointer;
  private final boolean quirks;
  private final boolean framesetOk;
  private final boolean dropsNextLineFeed;
  private final boolean pendingTableText;
  private final String signature;

  // activeFormattingAttributes is folded into the signature only: reconstruction and the Noah's
  // Ark clause compare attributes. framesetOk is left out of it, see
  // framesetOk().
  ParserState(
      String engine,
      OpenElement fragmentContext,
      boolean scripting,
      TokenizerState tokenizer,
      String attributeName,
      String endTagExpectation,
      boolean lastCR,
      String pendingInput,
      InsertionMode mode,
      InsertionMode originalMode,
      List<OpenElement> stack,
      List<Integer> activeFormatting,
      List<OpenElement> activeFormattingElements,
      List<List<String>> activeFormattingAttributes,
      List<InsertionMode> templateModes,
      boolean formPointer,
      boolean headPointer,
      boolean quirks,
      boolean framesetOk,
      boolean dropsNextLineFeed,
      boolean pendingTableText) {
    this.engine = engine;
    this.fragmentContext = fragmentContext;
    this.scripting = scripting;
    this.tokenizer = tokenizer;
    this.attributeName = attributeName;
    this.endTagExpectation = endTagExpectation;
    this.lastCR = lastCR;
    this.pendingInput = pendingInput;
    this.mode = mode;
    this.originalMode = originalMode;
    this.stack = Collections.unmodifiableList(stack);
    this.activeFormatting = Collections.unmodifiableList(activeFormatting);
    this.activeFormattingElements = Collections.unmodifiableList(activeFormattingElements);
    this.templateModes = Collections.unmodifiableList(templateModes);
    this.formPointer = formPointer;
    this.headPointer = headPointer;
    this.quirks = quirks;
    this.framesetOk = framesetOk;
    this.dropsNextLineFeed = dropsNextLineFeed;
    this.pendingTableText = pendingTableText;

    StringBuilder sb = new StringBuilder(128);
    // The engines parse some input differently (<select>), so their states never merge either.
    sb.append(engine).append(' ');
    // The fragment context decides what the parser does when the stack is back to its root (the
    // reset of the insertion mode) and, in current upstream, what a <select> or <input> does in a
    // <select> context, so states from different contexts never merge.
    sb.append(fragmentContext == null ? "document" : "fragment(" + fragmentContext + ")");
    sb.append(scripting ? " scripting" : " no-scripting");
    sb.append(" tok=").append(tokenizer);
    if (attributeName != null) {
      sb.append(" attr=").append(quote(attributeName));
    }
    if (endTagExpectation != null) {
      sb.append(" end=").append(endTagExpectation);
    }
    if (lastCR) {
      sb.append(" lastCR");
    }
    sb.append(" mode=").append(mode).append(" original=").append(originalMode);
    sb.append(" stack=").append(stack);
    sb.append(" formatting=[");
    for (int i = 0; i < activeFormatting.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      int index = activeFormatting.get(i);
      if (index == MARKER) {
        sb.append("marker");
        continue;
      }
      sb.append(activeFormattingElements.get(i));
      for (String a : activeFormattingAttributes.get(i)) {
        sb.append(' ').append(quote(a));
      }
      sb.append(index == NOT_ON_STACK ? "@-" : "@" + index);
    }
    sb.append(']');
    if (!templateModes.isEmpty()) {
      sb.append(" templates=").append(templateModes);
    }
    sb.append(formPointer ? " form" : "").append(headPointer ? " head" : "").append(quirks ? " quirks" : "");
    sb.append(dropsNextLineFeed ? " drop-lf" : "").append(pendingTableText ? " table-text" : "");
    if (!pendingInput.isEmpty()) {
      sb.append(" pending=").append(quote(pendingInput));
    }
    this.signature = sb.toString();
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20 || c == 0x7f) {
        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  /** The {@link Engine#name()} of the engine this state is from. */
  public String engine() {
    return engine;
  }

  /** The context element of fragment parsing, or null for document parsing. */
  public OpenElement fragmentContext() {
    return fragmentContext;
  }

  /** Whether this parser runs with scripting enabled ({@code noscript} parsing differs). */
  public boolean scripting() {
    return scripting;
  }

  public TokenizerState tokenizer() {
    return tokenizer;
  }

  /**
   * The current attribute's name, lower-cased, in the states after its name (after attribute
   * name, before attribute value, and the three attribute value states); otherwise null. Null too
   * when the attribute is a duplicate, which the parser drops.
   */
  public String attributeName() {
    return attributeName;
  }

  /** In raw text (RCDATA, RAWTEXT, script data), the element whose end tag ends it; otherwise null. */
  public String endTagExpectation() {
    return endTagExpectation;
  }

  /**
   * Inside a token, the input since the parser was last between tokens, prefixed by that point's
   * signature; empty between tokens (in data, RCDATA, RAWTEXT, script data, PLAINTEXT or CDATA).
   */
  public String pendingInput() {
    return pendingInput;
  }

  public InsertionMode mode() {
    return mode;
  }

  /** The mode to return to after {@link InsertionMode#TEXT} (and in vnu, a few others). */
  public InsertionMode originalMode() {
    return originalMode;
  }

  /** The stack of open elements, bottom (the root) to top (the current node). */
  public List<OpenElement> stack() {
    return stack;
  }

  /** The current node, or null if the stack is empty. */
  public OpenElement currentNode() {
    return stack.isEmpty() ? null : stack.get(stack.size() - 1);
  }

  /**
   * The list of active formatting elements, oldest first: per entry, the stack index of its
   * element, {@link #MARKER} or {@link #NOT_ON_STACK}.
   */
  public List<Integer> activeFormatting() {
    return activeFormatting;
  }

  /** The elements of {@link #activeFormatting()}, null for markers. */
  public List<OpenElement> activeFormattingElements() {
    return activeFormattingElements;
  }

  /**
   * True if text inserted now would first reconstruct the active formatting elements: an entry
   * after the last marker isn't on the stack.
   */
  public boolean reconstructionPending() {
    for (int i = activeFormatting.size() - 1; i >= 0; i--) {
      int index = activeFormatting.get(i);
      if (index == MARKER) {
        return false;
      }
      if (index == NOT_ON_STACK) {
        return true;
      }
    }
    return false;
  }

  /** The stack of template insertion modes, bottom to top. */
  public List<InsertionMode> templateModes() {
    return templateModes;
  }

  public boolean formPointer() {
    return formPointer;
  }

  public boolean headPointer() {
    return headPointer;
  }

  public boolean quirks() {
    return quirks;
  }

  /**
   * The frameset-ok flag. It is <em>not</em> part of the {@link #signature()}: it only decides what
   * a later {@code <frameset>} start tag does, which the verifier rejects outright, and
   * validator.nu doesn't track it independently of how the input is split (a run of whitespace
   * and then text in one chunk leaves it set; split before the text, it is cleared).
   */
  public boolean framesetOk() {
    return framesetOk;
  }

  /** A line feed right after {@code <pre>}, {@code <listing>} or {@code <textarea>} is dropped. */
  public boolean dropsNextLineFeed() {
    return dropsNextLineFeed;
  }

  /**
   * In a table mode, the parser holds back table text that isn't all whitespace: the next token
   * foster-parents it. (validator.nu's form of the "in table text" mode.)
   */
  public boolean pendingTableText() {
    return pendingTableText;
  }

  /** A deterministic description of the whole state, with names only. */
  public String signature() {
    return signature;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ParserState && signature.equals(((ParserState) o).signature);
  }

  @Override
  public int hashCode() {
    return signature.hashCode();
  }

  @Override
  public String toString() {
    return signature;
  }
}
