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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Message keys known at build time ({@code -Atessera.messages}). When the option is not set the
 * bundle is unchecked and every key is accepted (with a warning per template).
 *
 * <p>The option is a comma-separated list of Spring basenames, each the path of a bundle without
 * {@code .properties} (#134). A basename may carry a locale suffix, {@code messages_en}, meaning
 * basename {@code messages} with default locale {@code en}. For each basename the build loads
 * {@code <base>.properties} (if it exists) and every locale file beside it ({@code
 * <base>_fr.properties}, {@code <base>_pt_BR.properties}, ...; #236). A request in another locale
 * falls back to the default locale's file and then to the base file, so a key is known when it is
 * in the base file or the default locale's file of some basename; a locale file may leave keys out.
 * A message rendered as HTML is checked in every loaded file.
 */
public final class MessageBundle {
  /** {@code _<language>[_<COUNTRY>|_<region>][_<variant>]}, the suffix of a locale file. */
  private static final Pattern LOCALE_SUFFIX = Pattern.compile("_[a-z]{2,3}(_[A-Z]{2}|_[0-9]{3})?(_[A-Za-z0-9]+)?");

  /** A trailing locale on a basename: {@code messages_en}, {@code messages_pt_BR}. */
  private static final Pattern NAMED_LOCALE = Pattern.compile("(.+?)_([a-z]{2})(_[A-Z]{2}|_[0-9]{3})?");

  private static final Set<String> LANGUAGES = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));

  private static final String SUFFIX = ".properties";

  private final Set<String> keys;
  private final String source;
  /** Every loaded file (per basename: the base, the default locale, then locale files) to its entries. */
  private final Map<String, Map<String, String>> files;
  private final List<Path> skipped;

  private final Set<String> warnedFiles = new HashSet<>();

  private MessageBundle(Set<String> keys, String source, Map<String, Map<String, String>> files, List<Path> skipped) {
    this.keys = keys;
    this.source = source;
    this.files = files;
    this.skipped = skipped;
  }

  /** No bundle configured: keys are not checked. */
  public static MessageBundle unchecked() {
    return new MessageBundle(
        null, null, Collections.<String, Map<String, String>>emptyMap(), Collections.<Path>emptyList());
  }

  /** The properties file for {@code basePath}: {@code basePath + ".properties"}. */
  public static Path file(Path basePath) {
    return Paths.get(basePath + SUFFIX);
  }

  /** A basename split into its base path and default locale ({@code null} when none is named). */
  private static final class Base {
    final Path base;
    final String defaultLocale;

    Base(Path base, String defaultLocale) {
      this.base = base;
      this.defaultLocale = defaultLocale;
    }

    Path defaultFile() {
      return defaultLocale == null ? null : file(Paths.get(base + "_" + defaultLocale));
    }

    static Base parse(Path entry) {
      Path name = entry.getFileName();
      Matcher m = NAMED_LOCALE.matcher(name == null ? "" : name.toString());
      if (m.matches() && LANGUAGES.contains(m.group(2))) {
        String locale = m.group(2) + (m.group(3) == null ? "" : m.group(3));
        return new Base(entry.resolveSibling(m.group(1)), locale);
      }
      return new Base(entry, null);
    }
  }

  /**
   * Why the {@code -Atessera.messages} basename {@code entry} cannot be loaded, or null. A named
   * default locale's file must exist; without one, the base file must.
   */
  public static String problem(Path entry) {
    Base b = Base.parse(entry);
    if (b.defaultLocale != null) {
      return Files.isRegularFile(b.defaultFile()) ? null : "message bundle does not exist: " + b.defaultFile();
    }
    if (Files.isRegularFile(file(b.base))) {
      return null;
    }
    List<Path> locales = localeFiles(b.base);
    if (locales.isEmpty()) {
      return "message bundle does not exist: " + file(b.base);
    }
    String first = locales.get(0).getFileName().toString();
    return "message bundle does not exist: " + file(b.base) + "; it has locale files " + names(locales)
        + ", so name the default locale's file without .properties, e.g. " + entry.resolveSibling(
            first.substring(0, first.length() - SUFFIX.length()));
  }

  /** Loads one basename; see {@link #load(List)}. */
  public static MessageBundle load(Path entry) {
    return load(Collections.singletonList(entry));
  }

  /**
   * Loads every basename in {@code entries} (each already passed {@link #problem}): its base file
   * if present and its locale files.
   */
  public static MessageBundle load(List<Path> entries) {
    Map<String, Map<String, String>> files = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>();
    List<String> sources = new ArrayList<>();
    Set<Path> loaded = new HashSet<>();
    List<Base> bases = new ArrayList<>();
    for (Path entry : entries) {
      Base b = Base.parse(entry);
      bases.add(b);
      // The files every locale falls back to: they define which keys exist.
      for (Path f : Arrays.asList(file(b.base), b.defaultFile())) {
        if (f != null && Files.isRegularFile(f) && loaded.add(f.toAbsolutePath().normalize())) {
          Map<String, String> entriesOf = read(f);
          files.put(f.toString(), entriesOf);
          keys.addAll(entriesOf.keySet());
          sources.add(f.toString());
        }
      }
      for (Path locale : localeFiles(b.base)) {
        if (loaded.add(locale.toAbsolutePath().normalize())) {
          files.put(locale.toString(), read(locale));
        }
      }
    }
    List<Path> skipped = new ArrayList<>();
    for (Base b : bases) {
      for (Path p : list(b.base, "_")) {
        if (!loaded.contains(p.toAbsolutePath().normalize()) && !skipped.contains(p)) {
          skipped.add(p);
        }
      }
    }
    return new MessageBundle(Backports.setCopy(keys), String.join(", ", sources), files, skipped);
  }

  /** The locale files of {@code base}, sorted. */
  private static List<Path> localeFiles(Path base) {
    String prefix = base.getFileName().toString();
    List<Path> found = new ArrayList<>();
    for (Path p : list(base, "")) {
      String name = p.getFileName().toString();
      if (LOCALE_SUFFIX.matcher(name.substring(prefix.length(), name.length() - SUFFIX.length())).matches()) {
        found.add(p);
      }
    }
    return found;
  }

  /** The {@code .properties} files beside {@code base} named {@code <base><sep>...}, sorted. */
  private static List<Path> list(Path base, String sep) {
    Path dir = base.toAbsolutePath().getParent();
    String prefix = base.getFileName() + sep;
    List<Path> found = new ArrayList<>();
    if (dir == null || !Files.isDirectory(dir)) {
      return found;
    }
    try (Stream<Path> list = Files.list(dir)) {
      list.forEach(
          p -> {
            String name = p.getFileName().toString();
            if (name.startsWith(prefix)
                && name.endsWith(SUFFIX)
                && name.length() > base.getFileName().toString().length() + SUFFIX.length()
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

  private static String names(List<Path> files) {
    List<String> names = new ArrayList<>();
    for (Path p : files) {
      names.add(p.getFileName().toString());
    }
    return String.join(", ", names);
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
   * The value of {@code key} in every loaded file that has it (base and default-locale files
   * first), keyed by file path. Empty when unchecked.
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

  /** The files keys are checked against (base and default-locale files), or null when unchecked. */
  public String source() {
    return source;
  }

  /**
   * Files beside a basename named {@code <base>_*.properties} that are not loaded because their
   * suffix is not a locale ({@code messages_en-US.properties}); the processor warns about them.
   */
  public List<Path> skipped() {
    return skipped;
  }

  /**
   * True the first time it is called for {@code templateFile} on an unchecked bundle: the
   * message compiler warns once per template that its keys are not checked.
   */
  boolean firstUncheckedUse(String templateFile) {
    return !isChecked() && warnedFiles.add(templateFile);
  }
}
