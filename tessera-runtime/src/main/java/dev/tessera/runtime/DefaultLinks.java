package dev.tessera.runtime;

import java.util.function.UnaryOperator;

/**
 * {@link Links} for a fixed context path and an optional {@code encodeURL} hook.
 *
 * <p>The context path loses any trailing {@code /} ({@code "/"} is the root, {@code ""}), so
 * {@code /a} never becomes the protocol-relative {@code //a}. One that is neither empty nor a path
 * starting with a single {@code /}, or that has {@code \}, {@code ?}, {@code #}, a space or a
 * control character, is rejected with an {@link IllegalArgumentException}.
 */
public final class DefaultLinks implements Links {
  private final String contextPath;
  private final UnaryOperator<String> encoder;

  /** @param contextPath servlet context path, {@code ""} or {@code "/"} for root */
  public DefaultLinks(String contextPath) {
    this(contextPath, null);
  }

  /**
   * @param contextPath servlet context path, {@code ""} or {@code "/"} for root
   * @param encoder applied to every built link (such as {@code response::encodeURL}), or null
   */
  public DefaultLinks(String contextPath, UnaryOperator<String> encoder) {
    this.contextPath = checkContextPath(contextPath);
    this.encoder = encoder;
  }

  @Override
  public String contextPath() {
    return contextPath;
  }

  @Override
  public String encode(String url) {
    return encoder == null ? url : encoder.apply(url);
  }

  /** {@code contextPath} without trailing {@code /}; fails closed on anything but a plain path. */
  private static String checkContextPath(String contextPath) {
    if (contextPath == null) {
      throw new IllegalArgumentException("context path must not be null");
    }
    int end = contextPath.length();
    while (end > 0 && contextPath.charAt(end - 1) == '/') {
      end--;
    }
    String p = contextPath.substring(0, end);
    boolean ok = p.isEmpty() || (p.charAt(0) == '/' && p.charAt(1) != '/');
    for (int i = 0; ok && i < p.length(); i++) {
      char c = p.charAt(i);
      ok = c > ' ' && c != 0x7f && c != '\\' && c != '?' && c != '#';
    }
    if (!ok) {
      throw new IllegalArgumentException(
          "context path must be empty or a path starting with a single '/' (was '" + contextPath + "')");
    }
    return p;
  }
}
