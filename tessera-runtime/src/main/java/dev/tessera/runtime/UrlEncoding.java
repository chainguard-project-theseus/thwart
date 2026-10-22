package dev.tessera.runtime;

/** Percent-encoding used by link expressions (UTF-8, RFC 3986). */
public final class UrlEncoding {
  private UrlEncoding() {}

  /** Encodes a path variable value: everything except unreserved characters, so {@code /} becomes {@code %2F}. */
  public static String pathSegment(String value) {
    throw new UnsupportedOperationException("TODO(#6): UrlEncoding.pathSegment");
  }

  /** Encodes a query parameter name or value: {@code & = + # ?} and space are encoded. */
  public static String queryParam(String value) {
    throw new UnsupportedOperationException("TODO(#6): UrlEncoding.queryParam");
  }
}
