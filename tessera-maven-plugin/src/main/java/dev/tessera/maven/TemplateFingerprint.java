package dev.tessera.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The fingerprint the annotation processor writes after compiling the templates (#425): one line
 * per {@code **}{@code /*.html} file under the templates root, {@code <sha-256 of its bytes> <path
 * relative to the root>}, sorted by path, after a version line. A copy of tessera-compiler's {@code
 * dev.tessera.compiler.processor.TemplateFingerprint}, which reads the same files (its {@code
 * TemplateDiscovery}); both are tested against the same expected bytes.
 */
final class TemplateFingerprint {
  /** Where the processor writes it, relative to the class output directory. */
  static final String RESOURCE = "META-INF/tessera/templates.fingerprint";

  private TemplateFingerprint() {}

  static byte[] of(Path root) throws IOException {
    Map<String, String> byPath = new TreeMap<>();
    try (Stream<Path> s = Files.walk(root)) {
      for (Path f : (Iterable<Path>) s::iterator) {
        if (Files.isRegularFile(f) && f.toString().endsWith(".html")) {
          String rel = root.relativize(f).toString().replace(f.getFileSystem().getSeparator(), "/");
          byPath.put(rel, sha256(Files.readAllBytes(f)));
        }
      }
    }
    StringBuilder sb = new StringBuilder("tessera-templates 1\n");
    for (Map.Entry<String, String> e : byPath.entrySet()) {
      sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder(64);
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // every JDK has SHA-256
    }
  }
}
