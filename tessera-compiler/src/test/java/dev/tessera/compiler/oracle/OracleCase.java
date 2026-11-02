package dev.tessera.compiler.oracle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One template the oracle gate compiles, renders and checks. Seeds come from #320, generated cases
 * from #321; the gate that runs them is #324.
 *
 * @param id first 12 hex characters of the SHA-256 of {@code template}, stable across runs so
 *     {@code known-holes.txt} lines stay valid
 * @param name human-readable name (seed name or generator description)
 * @param template the template source
 * @param origin where the case came from
 */
public record OracleCase(String id, String name, String template, Origin origin) {

  /** Where a case came from. */
  public enum Origin {
    /** A hand-written seed (#320). */
    SEED,
    /** The generator's default enumeration (#321). */
    GENERATED,
    /** The generator's explore mode (#321); never part of {@code mvn verify}. */
    EXPLORE
  }

  public OracleCase {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(origin, "origin");
    if (!id.equals(idOf(template))) {
      throw new IllegalArgumentException("case id " + id + " is not the id of its template: " + name);
    }
  }

  /** A case whose id is computed from the template. */
  public static OracleCase of(String name, String template, Origin origin) {
    return new OracleCase(idOf(template), name, template, origin);
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
