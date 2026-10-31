package dev.tessera.runtime;

/**
 * Attribute helpers called by generated template code. Not intended for application code. The
 * results are still escaped by {@link Escapers} when written.
 */
public final class Attributes {
  private Attributes() {}

  /**
   * {@code t:classappend}: {@code base + " " + extra}. An empty or null {@code extra} leaves
   * {@code base} unchanged (null stays null, so the attribute is omitted); an empty or null
   * {@code base} yields {@code extra} alone.
   */
  public static String appendClass(Object base, Object extra) {
    String b = Escapers.toText(base);
    String e = Escapers.toText(extra);
    if (e.isEmpty()) {
      return base == null ? null : b;
    }
    return b.isEmpty() ? e : b + " " + e;
  }

  /**
   * The text of a computed {@code class} attribute ({@code t:class}, {@code t:classappend},
   * {@code t:attr="class=..."}, ...) without the tokens AngularJS could read as an {@code ng}
   * directive (#542): AngularJS reads directives from class values ({@code
   * class="ng-init: expr"}) and is not supported. A token (separated by ASCII whitespace) is
   * dropped when any run of {@code [A-Za-z0-9_-]} in it matches {@link HtmlPolicy#isAngularClass};
   * AngularJS takes each directive name from such a run, so {@code a.ng-init:x} is dropped too.
   * When a token is dropped, the others are joined by one space. The value is converted to text
   * once, so the text checked is the text written; null stays null.
   */
  public static String classes(Object value) {
    if (value == null) {
      return null;
    }
    String s = Escapers.toText(value);
    if (s.indexOf('n') < 0 && s.indexOf('N') < 0) {
      return s;
    }
    StringBuilder kept = new StringBuilder();
    boolean dropped = false;
    for (String token : s.split("[ \t\n\f\r]+")) {
      if (namesAngularDirective(token)) {
        dropped = true;
      } else if (!token.isEmpty()) {
        kept.append(kept.length() == 0 ? "" : " ").append(token);
      }
    }
    return dropped ? kept.toString() : s;
  }

  /** Whether a run of {@code [A-Za-z0-9_-]} in {@code token} is an {@link HtmlPolicy#isAngularClass}. */
  private static boolean namesAngularDirective(String token) {
    int n = token.length();
    int i = 0;
    while (i < n) {
      if (!isWordOrDash(token.charAt(i))) {
        i++;
        continue;
      }
      int start = i;
      while (i < n && isWordOrDash(token.charAt(i))) {
        i++;
      }
      if (HtmlPolicy.isAngularClass(token.substring(start, i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWordOrDash(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
  }
}
