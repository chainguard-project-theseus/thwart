package dev.chainguard.thwart.compiler.messages;

import dev.chainguard.thwart.compiler.Backports;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Message keys known at build time ({@code -Athwart.messages}). When the option is not set the
 * bundle is unchecked and every key is accepted (with a warning per template).
 *
 * <p>The option is a comma-separated list of Spring basenames, each the path of a bundle without
 * {@code .properties}. A basename may carry a locale suffix, {@code messages_en}, meaning
 * basename {@code messages} with default locale {@code en}. For each basename the build loads
 * {@code <base>.properties} (if it exists) and every locale file beside it ({@code
 * <base>_fr.properties}, {@code <base>_pt_BR.properties}, ...), named as Spring names them
 * for a {@link Locale}. A request in another locale falls back to the default locale's files
 * ({@code _pt_BR}, then {@code _pt}) and then to the base file
 * ({@code ReloadableResourceBundleMessageSource}), so a key is known when it is in the base file or
 * one of the default locale's files of some basename; a locale file may leave keys out. Spring
 * Boot's default {@code ResourceBundleMessageSource} falls back to the default locale only for a
 * locale with no file of its own, so {@link #firstFallbackGaps} names the locale files where it
 * would not find such a key. A message rendered as HTML is checked in every loaded file.
 */
public final class MessageBundle {
  /**
   * {@code _<language>[_<Script>][_<COUNTRY>|_<region>]} or {@code
   * _<language>[_<Script>](_<COUNTRY>|_<region>|_)_<variant>}, the suffix Spring and {@code
   * ResourceBundle} give a locale file ({@code _zh_Hant_TW}, {@code _en__POSIX}). The language must
   * be one {@link #LANGUAGES} knows.
   */
  private static final Pattern LOCALE_SUFFIX =
      Pattern.compile("_([a-z]{2,3})(_[A-Z][a-z]{3})?((_[A-Z]{2}|_[0-9]{3}|_)_[A-Za-z0-9]+|_[A-Z]{2}|_[0-9]{3})?");

  /** A trailing locale on a basename: {@code messages_en}, {@code messages_pt_BR}. */
  private static final Pattern NAMED_LOCALE = Pattern.compile("(.+?)_([a-z]{2})(_[A-Z]{2}|_[0-9]{3})?");

  /**
   * ISO 639 languages, the languages of the JVM's locales (three-letter ones such as {@code fil})
   * and both forms of the codes Java 17 renamed, so a file such as {@code messages_faq.properties}
   * is not taken for a locale.
   */
  private static final Set<String> LANGUAGES = languages();

  private static final String SUFFIX = ".properties";

  private final Set<String> keys;
  private final String source;
  /** Every loaded file (per basename: the base, the default locale, then locale files) to its entries. */
  private final Map<String, Map<String, String>> files;
  private final List<Path> skipped;
  private final List<Bundle> bundles;

  private final Set<String> warnedFiles = new HashSet<>();
  private final Set<String> warnedGaps = new HashSet<>();

  private MessageBundle(
      Set<String> keys, String source, Map<String, Map<String, String>> files, List<Path> skipped, List<Bundle> bundles) {
    this.keys = keys;
    this.source = source;
    this.files = files;
    this.skipped = skipped;
    this.bundles = bundles;
  }

  /** No bundle configured: keys are not checked. */
  public static MessageBundle unchecked() {
    return new MessageBundle(
        null,
        null,
        Collections.<String, Map<String, String>>emptyMap(),
        Collections.<Path>emptyList(),
        Collections.<Bundle>emptyList());
  }

  /** The properties file for {@code basePath}: {@code basePath + ".properties"}. */
  public static Path file(Path basePath) {
    return Paths.get(basePath + SUFFIX);
  }

  private static Set<String> languages() {
    Set<String> out = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));
    for (Locale l : Locale.getAvailableLocales()) {
      if (!l.getLanguage().isEmpty()) {
        out.add(l.getLanguage());
      }
    }
    out.addAll(Arrays.asList("he", "id", "yi", "iw", "in", "ji"));
    return out;
  }

  /**
   * A locale file suffix and the ones a request in that locale falls back to, most specific first:
   * {@code _pt_BR, _pt}; {@code _en__POSIX, _en}; {@code _zh_Hant_TW, _zh_Hant, _zh}.
   */
  private static List<String> chain(String suffix) {
    List<String> out = new ArrayList<>();
    String s = suffix;
    while (!s.isEmpty()) {
      out.add(s);
      s = s.substring(0, s.lastIndexOf('_'));
      while (s.endsWith("_")) {
        s = s.substring(0, s.length() - 1);
      }
    }
    return out;
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
      return defaultLocale == null ? null : localeFile("_" + defaultLocale);
    }

    Path localeFile(String suffix) {
      return file(Paths.get(base + suffix));
    }

    /** The default locale's file and the files it falls back to before the base file. */
    List<String> defaultChain() {
      return defaultLocale == null ? Collections.<String>emptyList() : chain("_" + defaultLocale);
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

  /** One loaded basename: its base file's entries (null when absent) and locale files by suffix. */
  private static final class Bundle {
    final Base base;
    final Map<String, String> baseEntries;
    final Map<String, Map<String, String>> locales;

    Bundle(Base base, Map<String, String> baseEntries, Map<String, Map<String, String>> locales) {
      this.base = base;
      this.baseEntries = baseEntries;
      this.locales = locales;
    }

    /**
     * Whether {@code ResourceBundleMessageSource} finds {@code key} for a request in the locale of
     * {@code suffix} (the base file aside): in the locale's own files if it has any, else in the
     * default locale's.
     */
    boolean resolves(String suffix, String key) {
      boolean own = false;
      for (String s : chain(suffix)) {
        Map<String, String> f = locales.get(s);
        if (f != null) {
          own = true;
          if (f.containsKey(key)) {
            return true;
          }
        }
      }
      if (own) {
        return false;
      }
      for (String s : base.defaultChain()) {
        Map<String, String> f = locales.get(s);
        if (f != null && f.containsKey(key)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Why the {@code -Athwart.messages} basename {@code entry} cannot be loaded, or null. A named
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
    List<Path> locales = new ArrayList<>(localeFiles(b.base).values());
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
    Map<Path, Map<String, String>> read = new HashMap<>();
    List<Bundle> bundles = new ArrayList<>();
    for (Path entry : entries) {
      Base b = Base.parse(entry);
      Map<String, Path> localeFiles = localeFiles(b.base);
      Map<String, Map<String, String>> locales = new HashMap<>();
      // The files every locale falls back to: they define which keys exist.
      List<Path> fallback = new ArrayList<>();
      fallback.add(file(b.base));
      for (String s : b.defaultChain()) {
        fallback.add(b.localeFile(s));
      }
      Map<String, String> baseEntries = null;
      for (Path f : fallback) {
        if (Files.isRegularFile(f)) {
          Map<String, String> entriesOf = read(f, read, files);
          keys.addAll(entriesOf.keySet());
          if (!sources.contains(f.toString())) {
            sources.add(f.toString());
          }
          if (f.equals(file(b.base))) {
            baseEntries = entriesOf;
          }
        }
      }
      for (Map.Entry<String, Path> locale : localeFiles.entrySet()) {
        locales.put(locale.getKey(), read(locale.getValue(), read, files));
      }
      bundles.add(new Bundle(b, baseEntries, locales));
    }
    Set<Path> loaded = read.keySet();
    List<Path> skipped = new ArrayList<>();
    for (Bundle bundle : bundles) {
      for (Path p : list(bundle.base.base, "_")) {
        if (!loaded.contains(p.toAbsolutePath().normalize()) && !skipped.contains(p)) {
          skipped.add(p);
        }
      }
    }
    return new MessageBundle(Backports.setCopy(keys), String.join(", ", sources), files, skipped, bundles);
  }

  /** The entries of {@code file}, read once; the first read also adds it to {@code files}. */
  private static Map<String, String> read(Path file, Map<Path, Map<String, String>> read, Map<String, Map<String, String>> files) {
    Path key = file.toAbsolutePath().normalize();
    Map<String, String> entries = read.get(key);
    if (entries == null) {
      entries = read(file);
      read.put(key, entries);
      files.put(file.toString(), entries);
    }
    return entries;
  }

  /** The locale files of {@code base} by suffix ({@code _fr}), sorted by file name. */
  private static Map<String, Path> localeFiles(Path base) {
    String prefix = base.getFileName().toString();
    Map<String, Path> found = new LinkedHashMap<>();
    for (Path p : list(base, "")) {
      String name = p.getFileName().toString();
      String suffix = name.substring(prefix.length(), name.length() - SUFFIX.length());
      Matcher m = LOCALE_SUFFIX.matcher(suffix);
      if (m.matches() && LANGUAGES.contains(m.group(1))) {
        found.put(suffix, p);
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
   * For a known {@code key} that is in no base file, the first time it is asked: the locale files
   * whose locale does not find it with Spring Boot's default {@code ResourceBundleMessageSource}
   * (a request in a locale with a file of its own falls back to that file's parents and the base
   * file, not to the default locale), by name; otherwise empty. {@code
   * ReloadableResourceBundleMessageSource} finds the key in the default locale's files.
   */
  Set<String> firstFallbackGaps(String key) {
    Set<String> gaps = new TreeSet<>();
    if (!isChecked() || !keys.contains(key) || !warnedGaps.add(key)) {
      return gaps;
    }
    Map<String, String> requests = new TreeMap<>(); // locale suffix -> a file of that locale
    for (Bundle b : bundles) {
      if (b.baseEntries != null && b.baseEntries.containsKey(key)) {
        return gaps;
      }
      for (String suffix : b.locales.keySet()) {
        if (!requests.containsKey(suffix)) {
          requests.put(suffix, b.base.localeFile(suffix).getFileName().toString());
        }
      }
    }
    for (Map.Entry<String, String> r : requests.entrySet()) {
      boolean found = false;
      for (Bundle b : bundles) {
        found |= b.resolves(r.getKey(), key);
      }
      if (!found) {
        gaps.add(r.getValue());
      }
    }
    return gaps;
  }

  /**
   * True the first time it is called for {@code templateFile} on an unchecked bundle: the
   * message compiler warns once per template that its keys are not checked.
   */
  boolean firstUncheckedUse(String templateFile) {
    return !isChecked() && warnedFiles.add(templateFile);
  }
}
