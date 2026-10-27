package dev.tessera.compiler.processor;

import dev.tessera.compiler.TemplateSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the processor compiled (#425): one line per template, {@code <sha-256 of its bytes> <path
 * relative to the templates root>}, sorted by path, after a version line. Written to {@link
 * #RESOURCE} in the class output after a successful compile. Maven runs javac only when Java
 * sources or the classpath change, so {@code tessera-maven-plugin} compares this with the templates
 * directory before {@code compile} and forces a recompile when they differ. The plugin has a copy
 * of this routine ({@code dev.tessera.maven.TemplateFingerprint}); both are tested against the same
 * expected bytes.
 */
final class TemplateFingerprint {
  static final String RESOURCE = "META-INF/tessera/templates.fingerprint";

  private TemplateFingerprint() {}

  static byte[] of(List<TemplateSource> sources) {
    Map<String, String> byPath = new TreeMap<>();
    for (TemplateSource s : sources) {
      // Decoding is strict UTF-8 (TemplateDiscovery), so this re-encoding is the file's bytes.
      byPath.put(s.path(), sha256(s.content().getBytes(StandardCharsets.UTF_8)));
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
