package dev.tessera.compiler.verify.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Parser} over one htmlparser build. Any exception or assertion error from the engine
 * becomes an {@link EngineException}, and the parser stays broken (fail closed).
 *
 * <p>Inside a token the engine keeps the partial token in private fields this class can't read
 * (and the parser source isn't patched to expose them). The state there is described instead by
 * the state when the parser was last between tokens plus the input since then, which determines
 * it exactly.
 */
final class VnuParser implements Parser {
  private final VnuEngine engine;
  private final RawParser raw;
  /** The state when the parser was last between tokens; null while it is between tokens. */
  private ParserState anchor;
  /** The input since {@link #anchor}. */
  private final StringBuilder pending;
  private ParserState cached;
  private EngineException broken;

  VnuParser(VnuEngine engine, RawParser raw) {
    this(engine, raw, null, new StringBuilder(), null);
  }

  private VnuParser(VnuEngine engine, RawParser raw, ParserState anchor, StringBuilder pending, ParserState cached) {
    this.engine = engine;
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
  public void feed(String chunk) throws EngineException {
    checkUsable();
    if (chunk.isEmpty()) {
      return;
    }
    try {
      if (raw.betweenTokens()) {
        anchor = state();
        pending.setLength(0);
      }
      char[] chars = chunk.toCharArray();
      cached = null;
      raw.feed(chars, 0, chars.length);
      if (raw.betweenTokens()) {
        anchor = null;
        pending.setLength(0);
      } else {
        pending.append(chunk);
      }
    } catch (EngineException e) {
      broken = e;
      throw e;
    } catch (Exception | AssertionError e) {
      broken = failure(engine, "parsing", e);
      throw broken;
    }
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
    try {
      return new VnuParser(engine, raw.fork(), anchor, new StringBuilder(pending), cached);
    } catch (Exception | AssertionError e) {
      throw failure(engine, "forking the parser", e);
    }
  }

  private ParserState read() throws Exception {
    TokenizerState tokenizer = tokenizerState(raw.tokenizerState());
    String pendingInput;
    if (raw.betweenTokens()) {
      pendingInput = "";
    } else if (anchor != null) {
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
