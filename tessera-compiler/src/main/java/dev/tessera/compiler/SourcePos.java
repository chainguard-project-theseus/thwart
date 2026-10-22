package dev.tessera.compiler;

/**
 * A position in a template file. {@code file} is the path relative to the templates root
 * (e.g. {@code owners/ownerDetails.html}); {@code line} and {@code column} are 1-based.
 */
public record SourcePos(String file, int line, int column) {

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
