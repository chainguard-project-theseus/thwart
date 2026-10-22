package dev.tessera.compiler;

/** Encodes values as Java source literals for generated code. */
public final class JavaStrings {

  private JavaStrings() {}

  /**
   * A Java string literal for {@code s}. Line terminators, quotes and backslashes use escape
   * sequences (never {@code \\u} escapes, which javac would decode before lexing); other C0
   * control characters use octal escapes, and DEL and non-ASCII characters use {@code \\uXXXX}.
   */
  public static String literal(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) {
            b.append(String.format("\\%03o", (int) c));
          } else if (c >= 0x7f) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    return b.append('"').toString();
  }
}
