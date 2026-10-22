package dev.tessera.compiler;

import java.util.Objects;

/**
 * A position in a template file. {@code file} is the path relative to the templates root
 * (e.g. {@code owners/ownerDetails.html}); {@code line} and {@code column} are 1-based.
 */
public final class SourcePos {
  private final String file;
  private final int line;
  private final int column;

  public SourcePos(String file, int line, int column) {
    this.file = file;
    this.line = line;
    this.column = column;
  }

  public String file() {
    return file;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SourcePos)) {
      return false;
    }
    SourcePos that = (SourcePos) o;
    return Objects.equals(file, that.file)
        && line == that.line
        && column == that.column;
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(file);
    h = 31 * h + Integer.hashCode(line);
    h = 31 * h + Integer.hashCode(column);
    return h;
  }

  /**
   * The position {@code offset} characters into {@code text}, where {@code text} starts at
   * this position. Used to map an offset inside an attribute value to a file position.
   */
  public SourcePos advance(CharSequence text, int offset) {
    int l = line;
    int c = column;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        l++;
        c = 1;
      } else {
        c++;
      }
    }
    return new SourcePos(file, l, c);
  }

  @Override
  public String toString() {
    return file + ":" + line + ":" + column;
  }
}
