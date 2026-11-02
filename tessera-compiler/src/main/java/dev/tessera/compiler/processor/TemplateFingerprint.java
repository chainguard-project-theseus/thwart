package dev.tessera.compiler.processor;

import dev.tessera.compiler.TemplateSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What the processor compiled: after a version line, one line per template, {@code
 * <sha-256 of its bytes> <path relative to the templates root>}, sorted by path; then, for each
 * {@code -Atessera.messages} basename in option order, a line {@code messages <its file name>}
 * followed by one {@code <sha-256> <file name>} line per message file beside it, sorted by name.
 * Written to {@link #RESOURCE} in the class output after a successful compile. Maven runs javac
 * only when Java sources or the classpath change, so {@code tessera-maven-plugin} compares this
 * with the templates directory and message files before {@code compile} and forces a recompile when
 * they differ. The plugin has a copy of this routine ({@code dev.tessera.maven.TemplateFingerprint});
 * both are tested against the same expected bytes. A change to the format bumps the version, so
 * an older record forces one recompile.
 */
final class TemplateFingerprint {
  /** Where the processor writes it, relative to the class output directory. */
  static final String RESOURCE = "META-INF/tessera/templates.fingerprint";

  /** The first line; the processor bumps it when the format changes. */
  static final String VERSION = "tessera-templates 2";

  private TemplateFingerprint() {}

  static byte[] of(List<TemplateSource> sources, List<Path> messages) throws IOException {
    Map<String, String> byPath = new TreeMap<>();
    for (TemplateSource s : sources) {
      // Decoding is strict UTF-8 (TemplateDiscovery), so this re-encoding is the file's bytes.
      byPath.put(s.path(), sha256(s.content().getBytes(StandardCharsets.UTF_8)));
    }
    StringBuilder sb = new StringBuilder(VERSION + "\n");
    for (Map.Entry<String, String> e : byPath.entrySet()) {
      sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
    }
    appendMessages(sb, messages);
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Each basename and the files of its family: {@code <p>.properties} and {@code
   * <p>_*.properties}, where {@code <p>} is the basename's file name up to its first {@code _}. That
   * is every file {@code MessageBundle} can read for it (the base file, a named default locale and
   * every locale file) and a few more, so it holds whatever locale suffixes the bundle accepts.
   */
  private static void appendMessages(StringBuilder sb, List<Path> messages) throws IOException {
    for (Path entry : messages) {
      Path fileName = entry.getFileName();
      String name = fileName == null ? "" : fileName.toString();
      sb.append("messages ").append(name).append('\n');
      int underscore = name.indexOf('_');
      String family = underscore < 0 ? name : name.substring(0, underscore);
      Map<String, String> byName = new TreeMap<>();
      Path dir = entry.toAbsolutePath().getParent();
      if (dir != null && Files.isDirectory(dir)) {
        try (Stream<Path> s = Files.list(dir)) {
          for (Path f : (Iterable<Path>) s::iterator) {
            String n = f.getFileName().toString();
            if (n.endsWith(".properties")
                && (n.equals(family + ".properties") || n.startsWith(family + "_"))
                && Files.isRegularFile(f)) {
              byName.put(n, sha256(Files.readAllBytes(f)));
            }
          }
        }
      }
      for (Map.Entry<String, String> e : byName.entrySet()) {
        sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
      }
    }
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
