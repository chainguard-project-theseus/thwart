package dev.tessera.compiler.verify.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Parser} over the engine build. Any exception or assertion error from the engine becomes
 * an {@link EngineException}, and the parser stays broken (fail closed).
 *
 * <p>Inside a token the engine keeps the partial token in private fields this class can't read
 * (and the parser source isn't patched to expose them). The state there is described instead by
 * the state when the parser was last between tokens plus the input since then, which determines
 * it exactly. The engine itself carries the partial token across buffers: a chunk may end anywhere
 * ({@code EnginesTest} checks that the state, the errors and the inserted elements don't depend on
 * where the input is split). A raw parser is never fed in place (each feed works on a fork), so
 * forks share them.
 */
final class VnuParser implements Parser {
  private final VnuEngine engine;
  /** The fragment parsing context, or null for document parsing. */
  private final OpenElement context;
  private RawParser raw;
  /** The state when the parser was last between tokens; null while it is between tokens. */
  private ParserState anchor;
  /** The input since {@link #anchor}. */
  private final StringBuilder pending;
  private ParserState cached;
  private EngineException broken;

  VnuParser(VnuEngine engine, RawParser raw) {
    this(engine, null, raw);
  }

  VnuParser(VnuEngine engine, OpenElement context, RawParser raw) {
    this(engine, context, raw, null, new StringBuilder(), null);
  }

  private VnuParser(VnuEngine engine, OpenElement context, RawParser raw, ParserState anchor,
      StringBuilder pending, ParserState cached) {
    this.engine = engine;
    this.context = context;
    this.raw = raw;
    this.anchor = anchor;
    this.pending = pending;
    this.cached = cached;
  }

  static EngineException failure(Engine engine, String what, Throwable cause) {
    return new EngineException(engine.name() + ": " + what + " failed: " + cause, cause);
  }

  private void checkUsable() throws EngineException {
    if (broken != null) {
      throw new EngineException(engine.name() + ": parser is unusable after an earlier failure", broken);
    }
  }

  @Override
  public void feed(String chunk, Listener listener) throws EngineException {
    checkUsable();
    if (chunk.isEmpty()) {
      return;
    }
    try {
      RawParser next = raw.fork();
      char[] chars = chunk.toCharArray();
      // After a CR at the end of the last chunk, the tokenizer drops an LF at the start of this one
      // and counts lines and columns from the character after it.
      int start = raw.lastCR() && chars[0] == '\n' ? 1 : 0;
      next.feed(chars, 0, chars.length, events(listener, next, chars, start));
      if (next.betweenTokens()) {
        anchor = null;
        pending.setLength(0);
      } else {
        if (anchor == null) {
          // The chunk ends mid-token, and the parser was between tokens before it: the anchor.
          anchor = state();
        }
        pending.append(chunk);
      }
      raw = next;
      cached = null;
    } catch (EngineException e) {
      broken = e;
      throw e;
    } catch (Exception | AssertionError e) {
      broken = failure(engine, "parsing", e);
      throw broken;
    }
  }

  /**
   * Passes what {@code raw} reports while it parses {@code chars} on to {@code listener}, at
   * offsets into {@code chars}; the tokenizer's line 1, column 1 is {@code chars[start]}.
   */
  private RawParser.RawListener events(final Listener listener, final RawParser raw, final char[] chars,
      final int start) {
    if (listener == null) {
      return null;
    }
    return new RawParser.RawListener() {
      /** Where line {@code curLine} starts in {@code chars}: events come in input order. */
      private int curLine = 1;
      private int curStart = start;

      /**
       * The index in {@code chars} of the character at {@code line} and {@code column} as the
       * tokenizer counts them (a line starts after LF, CR or CRLF, which is one line break; columns
       * count UTF-16 units from 1), clamped to the input.
       */
      private int offset(int line, int column) {
        if (line < curLine) {
          curLine = 1;
          curStart = start;
        }
        while (curLine < line && curStart < chars.length) {
          char c = chars[curStart++];
          if (c == '\r' && curStart < chars.length && chars[curStart] == '\n') {
            curStart++; // the tokenizer swallows the LF of a CRLF
          }
          if (c == '\n' || c == '\r') {
            curLine++;
          }
        }
        return Math.max(0, Math.min(curStart + Math.max(column, 1) - 1, chars.length - 1));
      }

      @Override
      public void error(String message, int line, int column) {
        listener.error(message, offset(line, column));
      }

      @Override
      public void pushed(final String namespace, final String localName, final boolean onStack, int line, int column) {
        listener.pushed(new Stack() {
          @Override
          public OpenElement context() {
            return context;
          }

          @Override
          public int size() {
            return raw.stackLength();
          }

          @Override
          public String namespace(int i) {
            return raw.stackNamespace(i);
          }

          @Override
          public String localName(int i) {
            return raw.stackName(i);
          }

          @Override
          public String insertedNamespace() {
            return namespace;
          }

          @Override
          public String insertedName() {
            return localName;
          }

          @Override
          public boolean insertedOnStack() {
            return onStack;
          }

          @Override
          public int templateModeCount() {
            return raw.templateModeCount();
          }

          @Override
          public InsertionMode templateMode(int i) {
            for (InsertionMode m : InsertionMode.values()) {
              if (m.name().equals(raw.templateMode(i))) {
                return m;
              }
            }
            // Fails the feed, and so the parser (fail closed).
            throw new IllegalStateException("unknown template insertion mode");
          }
        }, offset(line, column));
      }
    };
  }

  @Override
  public ParserState state() throws EngineException {
    checkUsable();
    if (cached != null) {
      return cached;
    }
    try {
      cached = read();
      return cached;
    } catch (EngineException e) {
      broken = e;
      throw e;
    } catch (Exception | AssertionError e) {
      broken = failure(engine, "reading the parser state", e);
      throw broken;
    }
  }

  @Override
  public Parser fork() throws EngineException {
    checkUsable();
    // Raw parsers are never fed in place (see the class comment), so the copy can share them.
    return new VnuParser(engine, context, raw, anchor, new StringBuilder(pending), cached);
  }

  private ParserState read() throws Exception {
    TokenizerState tokenizer = tokenizerState(raw.tokenizerState());
    String pendingInput;
    if (anchor == null && raw.betweenTokens()) {
      pendingInput = "";
    } else if (anchor != null && !raw.betweenTokens()) {
      pendingInput = anchor.signature() + "\n" + pending;
    } else {
      throw new EngineException(engine.name() + ": inside a token with no known start (" + tokenizer + ")");
    }
    RawParser.RawTree t = raw.tree();
    List<OpenElement> stack = new ArrayList<>(t.stackNames.length);
    for (int i = 0; i < t.stackNames.length; i++) {
      stack.add(new OpenElement(t.stackNamespaces[i], t.stackNames[i], t.stackHtmlIntegrationPoints[i]));
    }
    int n = t.formattingStackIndex.length;
    List<Integer> formatting = new ArrayList<>(n);
    List<OpenElement> formattingElements = new ArrayList<>(n);
    List<List<String>> formattingAttributes = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      int index = t.formattingStackIndex[i];
      formatting.add(index);
      if (index == ParserState.MARKER) {
        formattingElements.add(null);
        formattingAttributes.add(null);
      } else {
        formattingElements.add(new OpenElement(t.formattingNamespaces[i], t.formattingNames[i], false));
        String[] pairs = t.formattingAttributes[i];
        List<String> attributes = new ArrayList<>(pairs.length / 2);
        for (int k = 0; k < pairs.length; k += 2) {
          attributes.add(pairs[k] + "=" + pairs[k + 1]);
        }
        formattingAttributes.add(attributes);
      }
    }
    List<InsertionMode> templateModes = new ArrayList<>(t.templateModes.length);
    for (String m : t.templateModes) {
      templateModes.add(mode(m));
    }
    return new ParserState(
        engine.name(),
        context,
        raw.scripting(),
        tokenizer,
        raw.attributeName(),
        raw.endTagExpectation(),
        raw.lastCR(),
        pendingInput,
        mode(t.mode),
        mode(t.originalMode),
        stack,
        formatting,
        formattingElements,
        formattingAttributes,
        templateModes,
        t.formPointer,
        t.headPointer,
        t.quirks,
        t.framesetOk,
        t.needToDropLF,
        t.pendingTableText);
  }

  private TokenizerState tokenizerState(String name) throws EngineException {
    for (TokenizerState s : TokenizerState.values()) {
      if (s.name().equals(name)) {
        return s;
      }
    }
    throw new EngineException(engine.name() + ": unknown tokenizer state");
  }

  private InsertionMode mode(String name) throws EngineException {
    for (InsertionMode m : InsertionMode.values()) {
      if (m.name().equals(name)) {
        return m;
      }
    }
    throw new EngineException(engine.name() + ": unknown insertion mode");
  }

  @Override
  public String toString() {
    return engine.name();
  }
}
