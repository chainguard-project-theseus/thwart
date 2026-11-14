package dev.chainguard.thwart.compiler.oracle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One template the oracle gate (#324) compiles, renders and checks: a seed (#320).
 *
 * @param id first 12 hex characters of the SHA-256 of {@code template}, stable across runs so
 *     {@code known-holes.txt} lines stay valid
 * @param name the seed name
 * @param template the template source
 */
public record OracleCase(String id, String name, String template) {

  public OracleCase {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(template, "template");
    if (!id.equals(idOf(template))) {
      throw new IllegalArgumentException("case id " + id + " is not the id of its template: " + name);
    }
  }

  /** A case whose id is computed from the template. */
  public static OracleCase of(String name, String template) {
    return new OracleCase(idOf(template), name, template);
  }

  /** The stable case id of a template: the first 12 hex characters of its UTF-8 SHA-256. */
  public static String idOf(String template) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
