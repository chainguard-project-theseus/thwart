package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * An allow-list policy for {@link SafeHtml#sanitize(String, HtmlPolicy)}. Immutable and
 * thread-safe; build one once (a {@code static final} field) and reuse it.
 *
 * <p>Two policies are built in: {@link #BASIC}, the policy of {@link SafeHtml#sanitize(String)}
 * and {@code html.sanitize}, and {@link #RICH}, for editor and Markdown output, behind
 * {@code html.sanitizeRich}. {@link #builder()} makes others from the same closed set of
 * elements and attributes: a policy can only switch on things the sanitizer knows how to write
 * safely. Whatever the policy, the output never has {@code style}, {@code on*} handlers,
 * framework directive attributes, comments, or
 * script-capable, raw-text or foreign elements, and
 * it is balanced {@code FLOW} content in a closed content model: it closes everything it opens
 * and nests elements only as that model allows, so a browser parses it as written (#329, #592).
 *
 * <p>To give templates an application policy, return it from a function of your own, in a class
 * annotated {@code @TemplateFunctions("notices")}: a {@code @TemplateFunction} method
 * {@code SafeHtml html(Object s)} that calls {@code SafeHtml.sanitize(s.toString(), POLICY)}, with
 * {@code POLICY} a {@code static final} field built once.
 */
public final class HtmlPolicy {

  /**
   * Elements a policy may allow. {@code a} keeps a checked {@code href}, {@code img} a checked
   * {@code src}. There are no tables (#592): the builder rejects their parts.
   */
  public static final Set<String> ELEMENTS =
      unmodifiable(
          "a", "b", "i", "em", "strong", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
          "code", "kbd", "span", "br", "img", "p", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
          "blockquote", "ul", "ol", "li", "div", "hr");

  /**
   * Attributes a policy may allow, besides {@code a[href]}, {@code img[src]} and {@code class}
   * ({@link Builder#allowClasses}). Each is kept only on the elements listed here, and only with
   * a value of the stated form: {@code title} on any element and {@code alt} on {@code img} (any
   * text), {@code width}/{@code height} on {@code img} (digits), {@code start} and
   * {@code reversed} on {@code ol}, {@code value} on {@code li} (an integer), and {@code id} on any
   * element ({@code [A-Za-z0-9_-]}, written with the prefix {@value #DEFAULT_ID_PREFIX}).
   */
  public static final Set<String> ATTRIBUTES =
      unmodifiable("id", "title", "alt", "width", "height", "start", "reversed", "value");

  /** Link {@code rel} tokens a policy may add. */
  public static final Set<String> REL_TOKENS = unmodifiable("nofollow", "noopener", "noreferrer", "ugc");

  /**
   * The prefix written before every kept {@code id}, so user content cannot clobber
   * {@code window} or {@code document} properties or the page's own ids. It is fixed (#661).
   */
  public static final String DEFAULT_ID_PREFIX = "user-content-";

  /**
   * The policy of {@link SafeHtml#sanitize(String)} and {@code html.sanitize}: {@code p br hr
   * h1}-{@code h6 b i em strong u s sub sup small mark code pre blockquote ul ol li span div}
   * without attributes, and {@code a} with a checked {@code href} plus
   * {@code rel="nofollow noopener"}.
   */
  public static final HtmlPolicy BASIC =
      builder()
          .allowElements(
              "a", "b", "i", "em", "strong", "u", "s", "sub", "sup", "small", "mark", "code", "pre",
              "blockquote", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "span", "div",
              "br", "hr")
          .build();

  /**
   * The policy of {@code html.sanitizeRich}, for stored editor or Markdown output: {@link #BASIC}
   * plus {@code del ins kbd}, images ({@code img} with a checked {@code src}, {@code alt},
   * {@code width}, {@code height}), {@code ol start reversed}, {@code li value},
   * {@code title}, {@code id} (prefixed {@value #DEFAULT_ID_PREFIX}, with {@code href="#x"} links
   * rewritten to match) and {@code language-*} class tokens (code highlighting). An image is kept
   * only if its {@code src} is a same-origin path (one {@code /}, not {@code //} or {@code /\}), so
   * viewing stored content never sends the reader to another host. Links keep
   * {@code target="_blank"} and get {@code rel="nofollow noopener"}. For other classes, build a
   * policy with {@link Builder#allowClasses}.
   */
  public static final HtmlPolicy RICH =
      builder()
          .allowElements(ELEMENTS.toArray(new String[0]))
          .allowAttributes(ATTRIBUTES.toArray(new String[0]))
          .allowClasses("language-*")
          .allowLinkTarget()
          .build();

  /**
   * The fixed policy of HTML messages ({@code t:utext="#{key}"}, #677): phrasing markup only,
   * {@code b i em strong u s sub sup small mark code kbd span br} with a {@code title}, and
   * {@code a} with a checked {@code href}, {@code rel="noopener noreferrer"} and
   * {@code target="_blank"} kept. Phrasing content never closes a {@code <p>} or heading around it,
   * so a message can stand in one. Not configurable.
   */
  static final HtmlPolicy MESSAGE = message().allowElements("a").linkRel("noopener", "noreferrer").allowLinkTarget().build();

  /**
   * {@link #MESSAGE} without links: the compiler picks it for a key with no {@code <a} in any of
   * its bundle values, so the message can also stand inside a link.
   */
  static final HtmlPolicy MESSAGE_NO_LINKS = message().build();

  private static Builder message() {
    return builder()
        .allowElements("b", "i", "em", "strong", "u", "s", "sub", "sup", "small", "mark", "code", "kbd", "span", "br")
        .allowAttributes("title");
  }

  final Set<String> elements;
  final Set<String> attributes;
  private final Set<String> classNames;
  private final List<String> classPrefixes;
  /** The rel attribute written on a kept link ({@code " rel=\"...\""}), or empty. */
  final String rel;

  final boolean linkTarget;

  private HtmlPolicy(Builder b) {
    this.elements = Collections.unmodifiableSet(new HashSet<>(b.elements));
    this.attributes = Collections.unmodifiableSet(new HashSet<>(b.attributes));
    Set<String> names = new HashSet<>();
    List<String> prefixes = new ArrayList<>();
    for (String c : b.classes) {
      if (c.endsWith("*")) {
        prefixes.add(c.substring(0, c.length() - 1));
      } else {
        names.add(c);
      }
    }
    this.classNames = Collections.unmodifiableSet(names);
    this.classPrefixes = Collections.unmodifiableList(prefixes);
    this.linkTarget = b.linkTarget;
    Set<String> relTokens = new LinkedHashSet<>(b.rel);
    if (linkTarget) {
      relTokens.add("noopener");
    }
    StringBuilder sb = new StringBuilder();
    for (String t : relTokens) {
      sb.append(sb.length() == 0 ? " rel=\"" : " ").append(t);
    }
    this.rel = sb.length() == 0 ? "" : sb.append('"').toString();
  }

  /** {@code html} cleaned by this policy; {@code null} gives {@link SafeHtml#EMPTY}. */
  public SafeHtml sanitize(String html) {
    return SafeHtml.sanitize(html, this);
  }

  /** Whether class token {@code token} is of the safe form and allowed. */
  boolean allowsClass(String token) {
    if (!isClassToken(token)) {
      return false;
    }
    if (classNames.contains(token)) {
      return true;
    }
    for (String p : classPrefixes) {
      if (token.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  boolean allowsClasses() {
    return !classNames.isEmpty() || !classPrefixes.isEmpty();
  }

  /** A builder that allows nothing but text; add what the content needs. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds an {@link HtmlPolicy}. Every method rejects names outside the closed sets above. */
  public static final class Builder {
    private final Set<String> elements = new HashSet<>();
    private final Set<String> attributes = new HashSet<>();
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<String> rel = new LinkedHashSet<>(Arrays.asList("nofollow", "noopener"));
    private boolean linkTarget;

    private Builder() {}

    /**
     * Allows elements from {@link #ELEMENTS} (lower case); table parts throw. {@code li} needs
     * {@code ul} or {@code ol} to be kept.
     */
    public Builder allowElements(String... names) {
      for (String name : names) {
        elements.add(check(name, ELEMENTS, "element"));
      }
      return this;
    }

    /** Allows attributes from {@link #ATTRIBUTES} (lower case), on the elements listed there. */
    public Builder allowAttributes(String... names) {
      for (String name : names) {
        attributes.add(check(name, ATTRIBUTES, "attribute"));
      }
      return this;
    }

    /**
     * Allows {@code class} tokens: an exact name, or a prefix ending in {@code *}
     * ({@code "language-*"}). A bare {@code "*"} throws: it would give user content the page's
     * whole class namespace, for CSS overlays and class-initialised widgets (#661). Only tokens of
     * the form {@code [A-Za-z0-9_-]+} are ever kept; other tokens are dropped.
     */
    public Builder allowClasses(String... patterns) {
      for (String p : patterns) {
        String name = p.endsWith("*") ? p.substring(0, p.length() - 1) : p;
        if (!isClassToken(name)) { // a bare "*" leaves an empty name
          throw new IllegalArgumentException("not an allowed class pattern: " + p);
        }
        classes.add(p);
      }
      return this;
    }

    /**
     * The {@code rel} tokens added to every kept link, from {@link #REL_TOKENS}. The default is
     * {@code nofollow noopener}; no arguments add none (for HTML the application wrote itself).
     */
    public Builder linkRel(String... tokens) {
      rel.clear();
      for (String t : tokens) {
        rel.add(check(t, REL_TOKENS, "rel token"));
      }
      return this;
    }

    /** Keeps {@code target="_blank"} on links (any other target is dropped) and adds {@code noopener}. */
    public Builder allowLinkTarget() {
      this.linkTarget = true;
      return this;
    }

    public HtmlPolicy build() {
      return new HtmlPolicy(this);
    }

    private static String check(String name, Set<String> allowed, String what) {
      if (name == null || !allowed.contains(name)) {
        throw new IllegalArgumentException("not an allowed " + what + ": " + name + " (allowed: " + new TreeSet<>(allowed) + ")");
      }
      return name;
    }
  }

  /** Whether {@code s} is non-empty and only {@code [A-Za-z0-9_-]}. */
  static boolean isClassToken(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!isClassChar(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  static boolean isClassChar(char c) {
    return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '-';
  }

  static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /**
   * {@code s} with only A-Z lower-cased, as a browser folds names and keywords. Not
   * {@code toLowerCase}: that maps U+212A (Kelvin) to {@code k} and U+0130 to {@code i} plus a
   * combining dot, so a value a browser reads as another would match here.
   */
  static String lower(String s) {
    char[] cs = s.toCharArray();
    for (int i = 0; i < cs.length; i++) {
      if (cs[i] >= 'A' && cs[i] <= 'Z') {
        cs[i] = (char) (cs[i] + ('a' - 'A'));
      }
    }
    return new String(cs);
  }

  /**
   * {@code s} without leading and trailing HTML whitespace (TAB, LF, FF, CR, SPACE; also CSS
   * whitespace after preprocessing). Not {@code trim}: that also strips U+000B and every other C0
   * control, which a browser keeps.
   */
  static String stripWhitespace(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isWhitespace(s.charAt(start))) {
      start++;
    }
    while (end > start && isWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(start, end);
  }

  /** HTML whitespace: TAB, LF, FF, CR and SPACE. */
  static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  static Set<String> unmodifiable(String... names) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
  }
}
