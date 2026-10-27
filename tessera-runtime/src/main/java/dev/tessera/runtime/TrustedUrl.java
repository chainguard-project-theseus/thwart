package dev.tessera.runtime;

import java.util.Objects;

/**
 * A URL the application vouches for. URL attributes skip the scheme check for it, and resource
 * URL attributes ({@code <script src>}, {@code <iframe src>}, ...) accept it. As the
 * {@code values} list of an SVG {@code <animate>} it vouches for every {@code ;}-separated item.
 * Only constructed via {@link #unchecked(String)} (greppable, lint-bannable).
 */
public final class TrustedUrl {
  private final String url;

  private TrustedUrl(String url) {
    this.url = Objects.requireNonNull(url, "url");
  }

  public static TrustedUrl unchecked(String url) {
    return new TrustedUrl(url);
  }

  public String url() {
    return url;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TrustedUrl && url.equals(((TrustedUrl) o).url);
  }

  @Override
  public int hashCode() {
    return url.hashCode();
  }

  @Override
  public String toString() {
    return url;
  }
}
