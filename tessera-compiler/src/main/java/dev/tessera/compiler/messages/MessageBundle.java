package dev.tessera.compiler.messages;

import dev.tessera.compiler.Backports;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Message keys known at build time, from the default-locale bundle
 * ({@code -Atessera.messages=<base path>}, reading {@code <base>.properties}). When the option
 * is not set the bundle is unchecked and every key is accepted (with a warning per template).
 *
 * <p>The locale files next to it ({@code <base>_fr.properties}, {@code <base>_pt_BR.properties},
 * ...) are loaded too, so a message rendered as HTML can be checked in every locale the build can
 * see (#236).
 */
public final class MessageBundle {
  /** {@code _<language>[_<COUNTRY>|_<region>][_<variant>]}, the suffix of a locale file. */
  private static final Pattern LOCALE_SUFFIX = Pattern.compile("_[a-z]{2,3}(_[A-Z]{2}|_[0-9]{3})?(_[A-Za-z0-9]+)?");

  private final Set<String> keys;
  private final String source;
  /** Every loaded file (the default bundle first, then locale files by name) to its entries. */
  private final Map<String, Map<String, String>> files;

  private final Set<String> warnedFiles = new HashSet<>();

  private MessageBundle(Set<String> keys, String source, Map<String, Map<String, String>> files) {
    this.keys = keys;
    this.source = source;
    this.files = files;
  }

  /** No bundle configured: keys are not checked. */
  public static MessageBundle unchecked() {
    return new MessageBundle(null, null, Collections.<String, Map<String, String>>emptyMap());
  }

  /** The properties file for {@code basePath}: {@code basePath + ".properties"}. */
  public static Path file(Path basePath) {
    return Paths.get(basePath + ".properties");
  }

  /**
   * Loads {@code basePath + ".properties"} and the locale files beside it; a missing default file
   * is reported by the caller.
   */
  public static MessageBundle load(Path basePath) {
    Path file = file(basePath);
    Map<String, Map<String, String>> files = new LinkedHashMap<>();
    Map<String, String> defaults = read(file);
    files.put(file.toString(), defaults);
    for (Path locale : localeFiles(basePath)) {
      files.put(locale.toString(), read(locale));
    }
    return new MessageBundle(Backports.setCopy(defaults.keySet()), file.toString(), files);
  }

  private static List<Path> localeFiles(Path basePath) {
    Path dir = basePath.toAbsolutePath().getParent();
    String prefix = basePath.getFileName().toString();
    List<Path> found = new ArrayList<>();
    if (dir == null || !Files.isDirectory(dir)) {
      return found;
    }
    try (Stream<Path> list = Files.list(dir)) {
      list.forEach(
          p -> {
            String name = p.getFileName().toString();
            if (name.startsWith(prefix)
                && name.endsWith(".properties")
                && LOCALE_SUFFIX.matcher(name.substring(prefix.length(), name.length() - ".properties".length())).matches()
                && Files.isRegularFile(p)) {
              found.add(p);
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list message bundles in " + dir, e);
    }
    Collections.sort(found);
    return found;
  }

  private static Map<String, String> read(Path file) {
    Properties props = new Properties();
    try {
      // Spring Boot reads messages as UTF-8; malformed bytes only affect values, not ASCII keys.
      props.load(new StringReader(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read message bundle " + file, e);
    }
    Map<String, String> entries = new LinkedHashMap<>();
    for (String key : props.stringPropertyNames()) {
      entries.put(key, props.getProperty(key));
    }
    return entries;
  }

  public boolean isChecked() {
    return keys != null;
  }

  public boolean contains(String key) {
    return keys == null || keys.contains(key);
  }

  /**
   * The value of {@code key} in every loaded file that has it (the default bundle first), keyed by
   * file path. Empty when unchecked.
   */
  public Map<String, String> values(String key) {
    Map<String, String> found = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> f : files.entrySet()) {
      String v = f.getValue().get(key);
      if (v != null) {
        found.put(f.getKey(), v);
      }
    }
    return found;
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
