package dev.tessera.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * framework directive attributes, comments (but the empty ones that break {@code {{}), or
 * script-capable, raw-text or foreign elements, and
 * it is balanced {@code FLOW} content: it closes everything it opens and nests elements only
 * where a browser's parser keeps them as written, so it implies no end tags (#329).
 *
 * <p>To give templates an application policy, return it from a function of your own, in a class
 * annotated {@code @TemplateFunctions("notices")}: a {@code @TemplateFunction} method
 * {@code SafeHtml html(Object s)} that calls {@code SafeHtml.sanitize(s.toString(), POLICY)}, with
 * {@code POLICY} a {@code static final} field built once.
 */
public final class HtmlPolicy {

  /** Elements a policy may allow. {@code a} keeps a checked {@code href}, {@code img} a checked {@code src}. */
  public static final Set<String> ELEMENTS =
      unmodifiable(
          "a", "b", "i", "em", "strong", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
          "code", "kbd", "span", "br", "img", "p", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
          "blockquote", "ul", "ol", "li", "div", "hr", "table", "caption", "thead", "tbody", "tfoot",
          "tr", "th", "td");

  /**
   * Attributes a policy may allow, besides {@code a[href]}, {@code img[src]} and {@code class}
   * ({@link Builder#allowClasses}). Each is kept only on the elements listed here, and only with
   * a value of the stated form: {@code title} on any element and {@code alt} on {@code img} (any
   * text without {@code {{}), {@code width}/{@code height} on {@code img} and
   * {@code colspan}/{@code rowspan} on {@code td}/{@code th} (digits), {@code align} on
   * {@code td}/{@code th} ({@code left}, {@code center}, {@code right}, {@code justify}),
   * {@code start} and {@code reversed} on {@code ol}, {@code value} on {@code li} (an integer), and
   * {@code id} on any element ({@code [A-Za-z0-9_-]}, written with the policy's id prefix).
   */
  public static final Set<String> ATTRIBUTES =
      unmodifiable(
          "id", "title", "alt", "width", "height", "colspan", "rowspan", "align", "start",
          "reversed", "value");

  /** Link {@code rel} tokens a policy may add. */
  public static final Set<String> REL_TOKENS = unmodifiable("nofollow", "noopener", "noreferrer", "ugc");

  /** The id prefix unless {@link Builder#idPrefix} sets another. */
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
   * {@code width}, {@code height}), tables ({@code table caption thead tbody tfoot tr th td} with
   * {@code colspan}, {@code rowspan}, {@code align}), {@code ol start reversed}, {@code li value},
   * {@code title}, {@code id} (prefixed {@value #DEFAULT_ID_PREFIX}, with {@code href="#x"} links
   * rewritten to match) and {@code language-*} class tokens (code highlighting). An image is kept
   * only if its {@code src} is a same-origin path (one {@code /}, not {@code //} or {@code /\}), so
   * viewing stored content never sends the reader to another host. Links keep
   * {@code target="_blank"} and get {@code rel="nofollow noopener"}. For other classes or remote
   * images, build a policy with {@link Builder#allowClasses} and {@link Builder#allowRemoteImages}.
   */
  public static final HtmlPolicy RICH =
      builder()
          .allowElements(ELEMENTS.toArray(new String[0]))
          .allowAttributes(ATTRIBUTES.toArray(new String[0]))
          .allowClasses("language-*")
          .allowLinkTarget()
          .build();

  final Set<String> elements;
  final Set<String> attributes;
  private final Set<String> classNames;
  private final List<String> classPrefixes;
  private final boolean anyClass;
  final String idPrefix;
  /** The rel attribute written on a kept link ({@code " rel=\"...\""}), or empty. */
  final String rel;

  final boolean linkTarget;
  final boolean dataImages;
  final boolean remoteImages;

  private HtmlPolicy(Builder b) {
    this.elements = Collections.unmodifiableSet(new HashSet<>(b.elements));
    this.attributes = Collections.unmodifiableSet(new HashSet<>(b.attributes));
    Set<String> names = new HashSet<>();
    List<String> prefixes = new ArrayList<>();
    boolean any = false;
    for (String c : b.classes) {
      if (c.equals("*")) {
        any = true;
      } else if (c.endsWith("*")) {
        prefixes.add(c.substring(0, c.length() - 1));
      } else {
        names.add(c);
      }
    }
    this.classNames = Collections.unmodifiableSet(names);
    this.classPrefixes = Collections.unmodifiableList(prefixes);
    this.anyClass = any;
    this.idPrefix = b.idPrefix;
    this.linkTarget = b.linkTarget;
    this.dataImages = b.dataImages;
    this.remoteImages = b.remoteImages;
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

  /** Whether class token {@code token} (already of the safe form) is allowed. */
  boolean allowsClass(String token) {
    if (anyClass || classNames.contains(token)) {
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
    return anyClass || !classNames.isEmpty() || !classPrefixes.isEmpty();
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
    private String idPrefix = DEFAULT_ID_PREFIX;
    private boolean linkTarget;
    private boolean dataImages;
    private boolean remoteImages;

    private Builder() {}

    /**
     * Allows elements from {@link #ELEMENTS} (lower case). Table parts need {@code table}, and
     * {@code li} needs {@code ul} or {@code ol}, to be kept.
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
     * Allows {@code class} tokens: an exact name, a prefix ending in {@code *}
     * ({@code "language-*"}), or {@code "*"} for any token. Only tokens of the form
     * {@code [A-Za-z0-9_-]+} are ever kept, and never one AngularJS reads as a built-in directive
     * name ({@link #isAngularClass}); other tokens are dropped.
     */
    public Builder allowClasses(String... patterns) {
      for (String p : patterns) {
        String name = p.endsWith("*") ? p.substring(0, p.length() - 1) : p;
        if (!(p.equals("*") || isClassToken(name)) || isAngularClass(name)) {
          throw new IllegalArgumentException("not an allowed class pattern: " + p);
        }
        classes.add(p);
      }
      return this;
    }

    /**
     * The prefix written before every kept {@code id} (default {@value #DEFAULT_ID_PREFIX}), so
     * user content cannot clobber {@code window} or {@code document} properties or the page's own
     * ids. It must be a letter followed by {@code [A-Za-z0-9_-]} and end in {@code -}, so a kept id
     * is never a JavaScript identifier; an empty prefix isn't allowed.
     */
    public Builder idPrefix(String prefix) {
      if (prefix == null
          || prefix.length() < 2
          || !isAsciiLetter(prefix.charAt(0))
          || !isClassToken(prefix)
          || prefix.charAt(prefix.length() - 1) != '-') {
        throw new IllegalArgumentException("not an allowed id prefix: " + prefix);
      }
      this.idPrefix = prefix;
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

    /**
     * Images are kept only with a same-origin path {@code src} unless this is called; with it, any
     * {@code src} that passes the URL scheme check ({@code http}, {@code https}, or relative) is
     * kept. Remote images tell the image host the reader's IP address and the page they read.
     */
    public Builder allowRemoteImages() {
      this.remoteImages = true;
      return this;
    }

    /**
     * Also accepts {@code data:image/png}, {@code gif}, {@code jpeg} and {@code webp} base64 URLs
     * in {@code img src} (an image never runs script). Other {@code data:} URLs stay rejected.
     */
    public Builder allowDataImages() {
      this.dataImages = true;
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

  /**
   * Whether AngularJS could read class token {@code token} as one of its {@code ng} directives
   * (it reads directives from classes): after an optional {@code x-}/{@code data-} prefix (also
   * with {@code _}), {@code ng} (any case) followed by {@code -}, {@code _}, an upper-case letter
   * ({@code ngBind}) or nothing. These are the {@code ng-*}, {@code ng_*} rows of the framework
   * table (context-model.md 6.3), plus the camel-case form a class keeps.
   */
  static boolean isAngularClass(String token) {
    String l = lower(token);
    int start = l.startsWith("x-") || l.startsWith("x_") ? 2 : l.startsWith("data-") || l.startsWith("data_") ? 5 : 0;
    if (!l.startsWith("ng", start)) {
      return false;
    }
    if (token.length() == start + 2) {
      return true;
    }
    char c = token.charAt(start + 2);
    return c == '-' || c == '_' || (c >= 'A' && c <= 'Z');
  }

  /** Whether {@code s} is non-empty and only {@code [A-Za-z0-9_-]}. */
  static boolean isClassToken(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!(isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  private static Set<String> unmodifiable(String... names) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
  }
}
