package dev.tessera.compiler.internal.html5.classic.impl;

import dev.tessera.compiler.internal.html5.classic.common.XmlViolationPolicy;
import java.util.Arrays;
import org.xml.sax.SAXException;

/**
 * Tessera's accessor for this htmlparser build (#365). It lives in the parser's own package so it
 * can read the package-private and protected state the build-time verifier needs, without
 * patching the parser source. One instance is one parser: a tokenizer and a tree builder that
 * builds no tree.
 *
 * <p>Everything it returns is a JDK type. {@code dev.tessera.compiler.verify.engine} maps it to
 * Tessera's engine-independent {@code ParserState}. The current build has a copy of this class
 * that differs only in its package and in the two constant tables at the end.
 */
public final class TesseraAccess {

  private static final String HTML = "http://www.w3.org/1999/xhtml";

  /** Stack index of an active formatting entry that is a marker. */
  public static final int MARKER = -1;

  /** Stack index of an active formatting entry whose element is not on the stack. */
  public static final int NOT_ON_STACK = -2;

  private final boolean scripting;
  private final String contextNamespace;
  private final String contextName;
  private final NullTree tree = new NullTree();
  private final Tokenizer tokenizer;
  private boolean lastCR;

  private TesseraAccess(boolean scripting, String contextNamespace, String contextName)
      throws SAXException {
    this.scripting = scripting;
    this.contextNamespace = contextNamespace;
    this.contextName = contextName;
    tree.setScriptingEnabled(scripting);
    tree.setIgnoringComments(true);
    if (contextName != null) {
      tree.setFragmentContext(contextName.intern(), contextNamespace.intern(), null, false);
    }
    tokenizer = new Tokenizer(tree, false);
    tokenizer.setCommentPolicy(XmlViolationPolicy.ALLOW);
    tokenizer.setContentNonXmlCharPolicy(XmlViolationPolicy.ALLOW);
    tokenizer.setContentSpacePolicy(XmlViolationPolicy.ALLOW);
    tokenizer.setNamePolicy(XmlViolationPolicy.ALLOW);
    tokenizer.setXmlnsPolicy(XmlViolationPolicy.ALLOW);
    tree.setNamePolicy(XmlViolationPolicy.ALLOW);
    tokenizer.start();
    // A tokenizer allocates its buffers on its first tokenizeBuffer call, and
    // Tokenizer.loadState (fork) copies between them: make that call now, with no input.
    tokenizer.tokenizeBuffer(new UTF16Buffer(new char[0], 0, 0));
  }

  /** Document parsing from the initial state. */
  public static TesseraAccess document(boolean scripting) throws SAXException {
    return new TesseraAccess(scripting, null, null);
  }

  /**
   * Fragment parsing in the context of an element. {@code namespace} is the HTML, SVG or MathML
   * namespace URI.
   */
  public static TesseraAccess fragment(String namespace, String localName, boolean scripting)
      throws SAXException {
    if (namespace == null || localName == null) {
      throw new IllegalArgumentException("fragment context needs a namespace and a local name");
    }
    return new TesseraAccess(scripting, namespace, localName);
  }

  public boolean scripting() {
    return scripting;
  }

  /**
   * Tokenizes {@code chars[start, end)} without end-of-file processing, as vnu's own driver does:
   * a CR at the end of one chunk swallows an LF at the start of the next.
   */
  public void feed(char[] chars, int start, int end) throws SAXException {
    UTF16Buffer buffer = new UTF16Buffer(chars, start, end);
    while (buffer.hasMore()) {
      buffer.adjust(lastCR);
      lastCR = false;
      if (buffer.hasMore()) {
        lastCR = tokenizer.tokenizeBuffer(buffer);
      }
    }
  }

  /**
   * An independent parser in the same state, by the parser's own state-transfer methods (built
   * for speculative parsing): {@link TreeBuilder#loadState} and {@link Tokenizer#loadState}. Tree
   * nodes are opaque and never mutated, so the copies can share them.
   */
  public TesseraAccess fork() throws SAXException {
    TesseraAccess copy = new TesseraAccess(scripting, contextNamespace, contextName);
    copy.tree.loadState(tree.newSnapshot());
    if (tree.charBufferLen > 0) {
      copy.tree.charBuffer = Arrays.copyOf(tree.charBuffer, tree.charBuffer.length);
      copy.tree.charBufferLen = tree.charBufferLen;
    }
    copy.tokenizer.loadState(tokenizer);
    copy.lastCR = lastCR;
    return copy;
  }

  /** The tokenizer state as the name of its constant in {@link Tokenizer}, or null if unknown. */
  public String tokenizerState() {
    return tokenizerStateName(tokenizer.stateSave);
  }

  /** The current attribute's name, lower-cased; null in states that have none. */
  public String attributeName() {
    switch (tokenizer.stateSave) {
      case Tokenizer.AFTER_ATTRIBUTE_NAME:
      case Tokenizer.BEFORE_ATTRIBUTE_VALUE:
      case Tokenizer.ATTRIBUTE_VALUE_DOUBLE_QUOTED:
      case Tokenizer.ATTRIBUTE_VALUE_SINGLE_QUOTED:
      case Tokenizer.ATTRIBUTE_VALUE_UNQUOTED:
        // Null too after a duplicate attribute name: the parser drops that attribute.
        AttributeName name = tokenizer.attributeName;
        return name == null ? null : name.getLocal(AttributeName.HTML);
      default:
        return null;
    }
  }

  /**
   * True between tokens: the tokenizer is in a text state and holds no partial token. Only then
   * is its state fully described by what this class can read. In any other state the caller
   * describes it by the input since the last point between tokens.
   */
  public boolean betweenTokens() {
    switch (tokenizer.stateSave) {
      case Tokenizer.DATA:
      case Tokenizer.RCDATA:
      case Tokenizer.RAWTEXT:
      case Tokenizer.SCRIPT_DATA:
      case Tokenizer.SCRIPT_DATA_ESCAPED:
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPED:
      case Tokenizer.PLAINTEXT:
      case Tokenizer.CDATA_SECTION:
        return true;
      default:
        return false;
    }
  }

  /**
   * In RCDATA, RAWTEXT and script data, the element name whose end tag ends it; otherwise null
   * (the field keeps its last value after the raw text ends).
   */
  public String endTagExpectation() {
    switch (tokenizer.stateSave) {
      case Tokenizer.RCDATA:
      case Tokenizer.RAWTEXT:
      case Tokenizer.SCRIPT_DATA:
      case Tokenizer.SCRIPT_DATA_ESCAPED:
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPED:
        ElementName name = tokenizer.endTagExpectation;
        return name == null ? null : name.getName();
      default:
        return null;
    }
  }

  /** The last chunk ended in CR: an LF at the start of the next chunk is dropped. */
  public boolean lastCR() {
    return lastCR;
  }

  /** The tree builder's state, read through its public snapshot. */
  public Tree tree() throws SAXException {
    TreeBuilderState<Object> s = tree.newSnapshot();
    Tree t = new Tree();
    int stackLength = s.getStackLength();
    StackNode<Object>[] stack = s.getStack();
    t.stackNamespaces = new String[stackLength];
    t.stackNames = new String[stackLength];
    t.stackHtmlIntegrationPoints = new boolean[stackLength];
    for (int i = 0; i < stackLength; i++) {
      t.stackNamespaces[i] = stack[i].ns;
      t.stackNames[i] = stack[i].popName;
      t.stackHtmlIntegrationPoints[i] = stack[i].isHtmlIntegrationPoint();
    }
    int listLength = s.getListOfActiveFormattingElementsLength();
    StackNode<Object>[] list = s.getListOfActiveFormattingElements();
    t.formattingStackIndex = new int[listLength];
    t.formattingNamespaces = new String[listLength];
    t.formattingNames = new String[listLength];
    t.formattingAttributes = new String[listLength][];
    for (int i = 0; i < listLength; i++) {
      StackNode<Object> entry = list[i];
      if (entry == null) {
        t.formattingStackIndex[i] = MARKER;
        continue;
      }
      int index = NOT_ON_STACK;
      for (int j = stackLength - 1; j >= 0; j--) {
        if (stack[j].node == entry.node) {
          index = j;
          break;
        }
      }
      t.formattingStackIndex[i] = index;
      t.formattingNamespaces[i] = entry.ns;
      t.formattingNames[i] = entry.popName;
      HtmlAttributes attributes = entry.attributes;
      int n = attributes == null ? 0 : attributes.getLength();
      String[] pairs = new String[2 * n];
      for (int k = 0; k < n; k++) {
        pairs[2 * k] = attributes.getAttributeNameNoBoundsCheck(k).getLocal(AttributeName.HTML);
        pairs[2 * k + 1] = attributes.getValueNoBoundsCheck(k);
      }
      t.formattingAttributes[i] = pairs;
    }
    int[] templateModes = s.getTemplateModeStack();
    t.templateModes = new String[s.getTemplateModeStackLength()];
    for (int i = 0; i < t.templateModes.length; i++) {
      t.templateModes[i] = modeName(templateModes[i]);
    }
    t.mode = modeName(s.getMode());
    t.originalMode = modeName(s.getOriginalMode());
    t.framesetOk = s.isFramesetOk();
    t.formPointer = s.getFormPointer() != null;
    t.headPointer = s.getHeadPointer() != null;
    t.quirks = s.isQuirks();
    t.needToDropLF = s.isNeedToDropLF();
    t.pendingTableText = pendingTableText(s.getMode());
    return t;
  }

  /**
   * Characters the tree builder holds back in table modes: if any is not whitespace, the next
   * token foster-parents them all (and reconstructs the active formatting elements first).
   */
  private boolean pendingTableText(int mode) {
    if (mode != IN_TABLE && mode != IN_TABLE_BODY && mode != IN_ROW) {
      return false;
    }
    for (int i = 0; i < tree.charBufferLen; i++) {
      switch (tree.charBuffer[i]) {
        case ' ':
        case '\t':
        case '\n':
        case '\r':
        case '\u000C':
          continue;
        default:
          return true;
      }
    }
    return false;
  }

  /** The tree builder's state as plain values. Insertion modes are names, see {@link #modeName}. */
  public static final class Tree {
    public String[] stackNamespaces;
    public String[] stackNames;
    public boolean[] stackHtmlIntegrationPoints;
    /** Per entry: the stack index of its element, {@link #MARKER} or {@link #NOT_ON_STACK}. */
    public int[] formattingStackIndex;
    public String[] formattingNamespaces;
    public String[] formattingNames;
    /** Per entry: name, value, name, value, ...; null for a marker. */
    public String[][] formattingAttributes;
    public String[] templateModes;
    public String mode;
    public String originalMode;
    public boolean framesetOk;
    public boolean formPointer;
    public boolean headPointer;
    public boolean quirks;
    public boolean needToDropLF;
    public boolean pendingTableText;
  }

  /** A tree builder that builds nothing. Parser state never depends on the tree (no hasChildren calls). */
  private static final class NullTree extends TreeBuilder<Object> {
    @Override
    protected Object createElement(String ns, String name, HtmlAttributes attributes, Object intendedParent) {
      return new Object();
    }

    @Override
    protected Object createHtmlElementSetAsRoot(HtmlAttributes attributes) {
      return new Object();
    }

    @Override
    protected void detachFromParent(Object element) {}

    @Override
    protected boolean hasChildren(Object element) {
      throw new IllegalStateException("hasChildren is not used by this parser version");
    }

    @Override
    protected void appendElement(Object child, Object newParent) {}

    @Override
    protected void appendChildrenToNewParent(Object oldParent, Object newParent) {}

    @Override
    protected void insertFosterParentedChild(Object child, Object table, Object stackParent) {}

    @Override
    protected Object createAndInsertFosterParentedElement(
        String ns, String name, HtmlAttributes attributes, Object table, Object stackParent) {
      return new Object();
    }

    @Override
    protected void insertFosterParentedCharacters(char[] buf, int start, int length, Object table, Object stackParent) {}

    @Override
    protected void appendCharacters(Object parent, char[] buf, int start, int length) {}

    @Override
    protected void appendComment(Object parent, char[] buf, int start, int length) {}

    @Override
    protected void appendCommentToDocument(char[] buf, int start, int length) {}

    @Override
    protected void addAttributesToElement(Object element, HtmlAttributes attributes) {}
  }

  // The tree builder's insertion modes are private constants; these mirror them for this build.
  // EnginesTest checks each one against a parse that must reach it.
  private static final int IN_ROW = 0;
  private static final int IN_TABLE_BODY = 1;
  private static final int IN_TABLE = 2;

  /** The spec name of a tree builder mode, or null if this build has no such mode. */
  public static String modeName(int mode) {
    switch (mode) {
      case 0: return "IN_ROW";
      case 1: return "IN_TABLE_BODY";
      case 2: return "IN_TABLE";
      case 3: return "IN_CAPTION";
      case 4: return "IN_CELL";
      case 5: return "IN_BODY"; // vnu's FRAMESET_OK: in body, frameset-ok set
      case 6: return "IN_BODY";
      case 7: return "IN_HEAD";
      case 8: return "IN_HEAD_NOSCRIPT";
      case 9: return "IN_COLUMN_GROUP";
      case 10: return "IN_SELECT_IN_TABLE";
      case 11: return "IN_SELECT";
      case 12: return "AFTER_BODY";
      case 13: return "IN_FRAMESET";
      case 14: return "AFTER_FRAMESET";
      case 15: return "INITIAL";
      case 16: return "BEFORE_HTML";
      case 17: return "BEFORE_HEAD";
      case 18: return "AFTER_HEAD";
      case 19: return "AFTER_AFTER_BODY";
      case 20: return "AFTER_AFTER_FRAMESET";
      case 21: return "TEXT";
      case 22: return "IN_TEMPLATE";
      default: return null;
    }
  }

  /** The name of a public {@link Tokenizer} state constant, or null if unknown. */
  public static String tokenizerStateName(int state) {
    switch (state) {
      case Tokenizer.DATA: return "DATA";
      case Tokenizer.RCDATA: return "RCDATA";
      case Tokenizer.SCRIPT_DATA: return "SCRIPT_DATA";
      case Tokenizer.RAWTEXT: return "RAWTEXT";
      case Tokenizer.SCRIPT_DATA_ESCAPED: return "SCRIPT_DATA_ESCAPED";
      case Tokenizer.ATTRIBUTE_VALUE_DOUBLE_QUOTED: return "ATTRIBUTE_VALUE_DOUBLE_QUOTED";
      case Tokenizer.ATTRIBUTE_VALUE_SINGLE_QUOTED: return "ATTRIBUTE_VALUE_SINGLE_QUOTED";
      case Tokenizer.ATTRIBUTE_VALUE_UNQUOTED: return "ATTRIBUTE_VALUE_UNQUOTED";
      case Tokenizer.PLAINTEXT: return "PLAINTEXT";
      case Tokenizer.TAG_OPEN: return "TAG_OPEN";
      case Tokenizer.CLOSE_TAG_OPEN: return "CLOSE_TAG_OPEN";
      case Tokenizer.TAG_NAME: return "TAG_NAME";
      case Tokenizer.BEFORE_ATTRIBUTE_NAME: return "BEFORE_ATTRIBUTE_NAME";
      case Tokenizer.ATTRIBUTE_NAME: return "ATTRIBUTE_NAME";
      case Tokenizer.AFTER_ATTRIBUTE_NAME: return "AFTER_ATTRIBUTE_NAME";
      case Tokenizer.BEFORE_ATTRIBUTE_VALUE: return "BEFORE_ATTRIBUTE_VALUE";
      case Tokenizer.AFTER_ATTRIBUTE_VALUE_QUOTED: return "AFTER_ATTRIBUTE_VALUE_QUOTED";
      case Tokenizer.BOGUS_COMMENT: return "BOGUS_COMMENT";
      case Tokenizer.MARKUP_DECLARATION_OPEN: return "MARKUP_DECLARATION_OPEN";
      case Tokenizer.DOCTYPE: return "DOCTYPE";
      case Tokenizer.BEFORE_DOCTYPE_NAME: return "BEFORE_DOCTYPE_NAME";
      case Tokenizer.DOCTYPE_NAME: return "DOCTYPE_NAME";
      case Tokenizer.AFTER_DOCTYPE_NAME: return "AFTER_DOCTYPE_NAME";
      case Tokenizer.BEFORE_DOCTYPE_PUBLIC_IDENTIFIER: return "BEFORE_DOCTYPE_PUBLIC_IDENTIFIER";
      case Tokenizer.DOCTYPE_PUBLIC_IDENTIFIER_DOUBLE_QUOTED: return "DOCTYPE_PUBLIC_IDENTIFIER_DOUBLE_QUOTED";
      case Tokenizer.DOCTYPE_PUBLIC_IDENTIFIER_SINGLE_QUOTED: return "DOCTYPE_PUBLIC_IDENTIFIER_SINGLE_QUOTED";
      case Tokenizer.AFTER_DOCTYPE_PUBLIC_IDENTIFIER: return "AFTER_DOCTYPE_PUBLIC_IDENTIFIER";
      case Tokenizer.BEFORE_DOCTYPE_SYSTEM_IDENTIFIER: return "BEFORE_DOCTYPE_SYSTEM_IDENTIFIER";
      case Tokenizer.DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED: return "DOCTYPE_SYSTEM_IDENTIFIER_DOUBLE_QUOTED";
      case Tokenizer.DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED: return "DOCTYPE_SYSTEM_IDENTIFIER_SINGLE_QUOTED";
      case Tokenizer.AFTER_DOCTYPE_SYSTEM_IDENTIFIER: return "AFTER_DOCTYPE_SYSTEM_IDENTIFIER";
      case Tokenizer.BOGUS_DOCTYPE: return "BOGUS_DOCTYPE";
      case Tokenizer.COMMENT_START: return "COMMENT_START";
      case Tokenizer.COMMENT_START_DASH: return "COMMENT_START_DASH";
      case Tokenizer.COMMENT: return "COMMENT";
      case Tokenizer.COMMENT_END_DASH: return "COMMENT_END_DASH";
      case Tokenizer.COMMENT_END: return "COMMENT_END";
      case Tokenizer.COMMENT_END_BANG: return "COMMENT_END_BANG";
      case Tokenizer.NON_DATA_END_TAG_NAME: return "NON_DATA_END_TAG_NAME";
      case Tokenizer.MARKUP_DECLARATION_HYPHEN: return "MARKUP_DECLARATION_HYPHEN";
      case Tokenizer.MARKUP_DECLARATION_OCTYPE: return "MARKUP_DECLARATION_OCTYPE";
      case Tokenizer.DOCTYPE_UBLIC: return "DOCTYPE_UBLIC";
      case Tokenizer.DOCTYPE_YSTEM: return "DOCTYPE_YSTEM";
      case Tokenizer.AFTER_DOCTYPE_PUBLIC_KEYWORD: return "AFTER_DOCTYPE_PUBLIC_KEYWORD";
      case Tokenizer.BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS: return "BETWEEN_DOCTYPE_PUBLIC_AND_SYSTEM_IDENTIFIERS";
      case Tokenizer.AFTER_DOCTYPE_SYSTEM_KEYWORD: return "AFTER_DOCTYPE_SYSTEM_KEYWORD";
      case Tokenizer.CONSUME_CHARACTER_REFERENCE: return "CONSUME_CHARACTER_REFERENCE";
      case Tokenizer.CONSUME_NCR: return "CONSUME_NCR";
      case Tokenizer.CHARACTER_REFERENCE_TAIL: return "CHARACTER_REFERENCE_TAIL";
      case Tokenizer.HEX_NCR_LOOP: return "HEX_NCR_LOOP";
      case Tokenizer.DECIMAL_NRC_LOOP: return "DECIMAL_NRC_LOOP";
      case Tokenizer.HANDLE_NCR_VALUE: return "HANDLE_NCR_VALUE";
      case Tokenizer.HANDLE_NCR_VALUE_RECONSUME: return "HANDLE_NCR_VALUE_RECONSUME";
      case Tokenizer.CHARACTER_REFERENCE_HILO_LOOKUP: return "CHARACTER_REFERENCE_HILO_LOOKUP";
      case Tokenizer.SELF_CLOSING_START_TAG: return "SELF_CLOSING_START_TAG";
      case Tokenizer.CDATA_START: return "CDATA_START";
      case Tokenizer.CDATA_SECTION: return "CDATA_SECTION";
      case Tokenizer.CDATA_RSQB: return "CDATA_RSQB";
      case Tokenizer.CDATA_RSQB_RSQB: return "CDATA_RSQB_RSQB";
      case Tokenizer.SCRIPT_DATA_LESS_THAN_SIGN: return "SCRIPT_DATA_LESS_THAN_SIGN";
      case Tokenizer.SCRIPT_DATA_ESCAPE_START: return "SCRIPT_DATA_ESCAPE_START";
      case Tokenizer.SCRIPT_DATA_ESCAPE_START_DASH: return "SCRIPT_DATA_ESCAPE_START_DASH";
      case Tokenizer.SCRIPT_DATA_ESCAPED_DASH: return "SCRIPT_DATA_ESCAPED_DASH";
      case Tokenizer.SCRIPT_DATA_ESCAPED_DASH_DASH: return "SCRIPT_DATA_ESCAPED_DASH_DASH";
      case Tokenizer.BOGUS_COMMENT_HYPHEN: return "BOGUS_COMMENT_HYPHEN";
      case Tokenizer.RAWTEXT_RCDATA_LESS_THAN_SIGN: return "RAWTEXT_RCDATA_LESS_THAN_SIGN";
      case Tokenizer.SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN: return "SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN";
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPE_START: return "SCRIPT_DATA_DOUBLE_ESCAPE_START";
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPED: return "SCRIPT_DATA_DOUBLE_ESCAPED";
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN: return "SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN";
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPED_DASH: return "SCRIPT_DATA_DOUBLE_ESCAPED_DASH";
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH: return "SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH";
      case Tokenizer.SCRIPT_DATA_DOUBLE_ESCAPE_END: return "SCRIPT_DATA_DOUBLE_ESCAPE_END";
      case Tokenizer.AMBIGUOUS_AMPERSAND: return "AMBIGUOUS_AMPERSAND";
      case Tokenizer.PROCESSING_INSTRUCTION: return "PROCESSING_INSTRUCTION";
      case Tokenizer.PROCESSING_INSTRUCTION_QUESTION_MARK: return "PROCESSING_INSTRUCTION_QUESTION_MARK";
      case Tokenizer.COMMENT_LESSTHAN: return "COMMENT_LESSTHAN";
      case Tokenizer.COMMENT_LESSTHAN_BANG: return "COMMENT_LESSTHAN_BANG";
      case Tokenizer.COMMENT_LESSTHAN_BANG_DASH: return "COMMENT_LESSTHAN_BANG_DASH";
      case Tokenizer.COMMENT_LESSTHAN_BANG_DASH_DASH: return "COMMENT_LESSTHAN_BANG_DASH_DASH";
      default: return null;
    }
  }
}
