package dev.tessera.compiler.oracle;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shape of a URL attribute value as a browser's URL parser sees it (#318): after stripping
 * leading and trailing C0 controls and spaces and dropping tab, LF and CR, with nothing further
 * decoded.
 */
public final class UrlShape {
  private UrlShape() {}

  private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

  /** {@code url} after a browser's preprocessing: C0 and space trimmed, tab, LF and CR removed. */
  public static String preprocess(String url) {
    int start = 0;
    int end = url.length();
    while (start < end && url.charAt(start) <= ' ') {
      start++;
    }
    while (end > start && url.charAt(end - 1) <= ' ') {
      end--;
    }
    StringBuilder sb = new StringBuilder(end - start);
    for (int i = start; i < end; i++) {
      char c = url.charAt(i);
      if (c != '\t' && c != '\n' && c != '\r') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** The lower-case scheme of {@code url} after preprocessing, or null for a relative URL. */
  public static String scheme(String url) {
    Matcher m = SCHEME.matcher(preprocess(url));
    return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
  }

  /** Whether {@code url} has a {@code javascript:}, {@code vbscript:} or {@code data:} scheme. */
  public static boolean scriptScheme(String url) {
    String s = scheme(url);
    return "javascript".equals(s) || "vbscript".equals(s) || "data".equals(s);
  }

  /**
   * Whether {@code url} starts with two slashes once {@code \} reads as {@code /}, and at least one
   * of them is a backslash ({@code /\x}, {@code \\x}, {@code \/x}), with or without a special scheme
   * before them ({@code http:\\x}): a browser goes to host {@code x}. No template writes this on
   * purpose.
   */
  public static boolean backslashNetworkPath(String url) {
    String s = preprocess(url);
    String scheme = scheme(s);
    if (scheme != null) {
      if (!SPECIAL_SCHEMES.contains(scheme)) {
        return false;
      }
      s = s.substring(scheme.length() + 1);
    }
    return s.length() >= 2 && slash(s.charAt(0)) && slash(s.charAt(1)) && (s.charAt(0) == '\\' || s.charAt(1) == '\\');
  }

  /** The URL standard's special schemes, where a browser reads {@code \} as {@code /}. */
  private static final Set<String> SPECIAL_SCHEMES = Set.of("http", "https", "ws", "wss", "ftp", "file");

  /**
   * Whether {@code url} leaves the page's origin by shape: protocol-relative once {@code \} reads as
   * {@code /} ({@code //x}, {@code /\x}, ...) or absolute (it has a scheme).
   */
  public static boolean crossOrigin(String url) {
    String s = preprocess(url);
    return (s.length() >= 2 && slash(s.charAt(0)) && slash(s.charAt(1))) || scheme(s) != null;
  }

  private static boolean slash(char c) {
    return c == '/' || c == '\\';
  }
}
