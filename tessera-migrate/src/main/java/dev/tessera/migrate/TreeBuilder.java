package dev.tessera.migrate;

import java.util.List;
import java.util.Locale;
import org.attoparser.AbstractMarkupHandler;

/**
 * Builds an element tree from {@link Markup#PARSER} events, for the passes that need tags as
 * written: element names are lower-cased, void elements and {@code <x/>} have no children, and an
 * end tag closes the innermost open element of that name and everything inside it (a stray end
 * tag is ignored). Subclasses make the nodes and decide what each one keeps.
 */
abstract class TreeBuilder<N> extends AbstractMarkupHandler {
  final N root;
  private N current;
  private N pending;
  private boolean pendingClosed;

  TreeBuilder(N root) {
    this.root = root;
    this.current = root;
  }

  /** A new element named {@code name}, whose name is {@code buffer[nameOffset, nameEnd)}. */
  abstract N newNode(String name, int line, N parent, int nameOffset, int nameEnd);

  abstract String name(N node);

  abstract N parent(N node);

  abstract List<N> children(N node);

  /** An attribute of {@code node}'s start tag; {@code operatorLen} is 0 if it has no value. */
  abstract void attribute(N node, char[] buffer, int nameOffset, int nameLen, int operatorLen,
      int valueContentOffset, int valueContentLen);

  /** Called when {@code node} is closed by the end tag whose name starts at {@code nameOffset}. */
  void closed(N node, int nameOffset, int line) {}

  private void start(char[] buffer, int offset, int len, int line, boolean standalone) {
    String name = new String(buffer, offset, len).toLowerCase(Locale.ROOT);
    pending = newNode(name, line, current, offset, offset + len);
    pendingClosed = standalone || Markup.VOID_ELEMENTS.contains(name);
    children(current).add(pending);
  }

  private void end() {
    if (pending != null && !pendingClosed) {
      current = pending;
    }
    pending = null;
  }

  @Override
  public void handleOpenElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
    start(buffer, nameOffset, nameLen, line, false);
  }

  @Override
  public void handleOpenElementEnd(char[] buffer, int nameOffset, int nameLen, int line, int col) {
    end();
  }

  @Override
  public void handleStandaloneElementStart(
      char[] buffer, int nameOffset, int nameLen, boolean minimized, int line, int col) {
    start(buffer, nameOffset, nameLen, line, true);
  }

  @Override
  public void handleStandaloneElementEnd(
      char[] buffer, int nameOffset, int nameLen, boolean minimized, int line, int col) {
    end();
  }

  @Override
  public void handleCloseElementStart(char[] buffer, int nameOffset, int nameLen, int line, int col) {
    close(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT), nameOffset, line);
  }

  @Override
  public void handleUnmatchedCloseElementStart(
      char[] buffer, int nameOffset, int nameLen, int line, int col) {
    close(new String(buffer, nameOffset, nameLen).toLowerCase(Locale.ROOT), nameOffset, line);
  }

  /** Closes the innermost open element of that name and everything inside it; else ignored. */
  private void close(String name, int nameOffset, int line) {
    for (N n = current; n != root; n = parent(n)) {
      if (name(n).equals(name)) {
        closed(n, nameOffset, line);
        current = parent(n);
        return;
      }
    }
  }

  @Override
  public void handleAttribute(
      char[] buffer,
      int nameOffset, int nameLen, int nameLine, int nameCol,
      int operatorOffset, int operatorLen, int operatorLine, int operatorCol,
      int valueContentOffset, int valueContentLen, int valueOuterOffset, int valueOuterLen,
      int valueLine, int valueCol) {
    if (pending != null) {
      attribute(pending, buffer, nameOffset, nameLen, operatorLen, valueContentOffset, valueContentLen);
    }
  }
}
