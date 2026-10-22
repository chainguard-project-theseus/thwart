package dev.tessera.compiler.messages;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Message keys known at build time, from the default-locale bundle
 * ({@code -Atessera.messages=<base path>}, reading {@code <base>.properties}). When the option
 * is not set the bundle is unchecked and every key is accepted (with a warning per template).
 */
public final class MessageBundle {
  private final Set<String> keys;
  private final String source;
  private final Set<String> warnedFiles = new HashSet<>();

  private MessageBundle(Set<String> keys, String source) {
    this.keys = keys;
    this.source = source;
  }

  /** No bundle configured: keys are not checked. */
  public static MessageBundle unchecked() {
    return new MessageBundle(null, null);
  }

  /** The properties file for {@code basePath}: {@code basePath + ".properties"}. */
  public static Path file(Path basePath) {
    return Path.of(basePath + ".properties");
  }

  /** Loads {@code basePath + ".properties"}; a missing file is reported by the caller. */
  public static MessageBundle load(Path basePath) {
    Path file = file(basePath);
    Properties props = new Properties();
    try {
      // Spring Boot reads messages as UTF-8; malformed bytes only affect values, not ASCII keys.
      props.load(new StringReader(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read message bundle " + file, e);
    }
    return new MessageBundle(Set.copyOf(props.stringPropertyNames()), file.toString());
  }

  public boolean isChecked() {
    return keys != null;
  }

  public boolean contains(String key) {
    return keys == null || keys.contains(key);
  }

  /** The bundle file keys are checked against, or null when unchecked. */
  public String source() {
    return source;
  }

  /**
   * True the first time it is called for {@code templateFile} on an unchecked bundle: the
   * message compiler warns once per template that its keys are not checked.
   */
  boolean firstUncheckedUse(String templateFile) {
    return !isChecked() && warnedFiles.add(templateFile);
  }
}
