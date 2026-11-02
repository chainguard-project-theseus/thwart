package dev.tessera.runtime;

import dev.tessera.runtime.internal.Ascii;
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
 * and nests elements only as that model allows, so a browser parses it as written.
 *
 * <p>To give templates an application policy, return it from a function of your own, in a class
 * annotated {@code @TemplateFunctions("notices")}: a {@code @TemplateFunction} method
 * {@code SafeHtml html(Object s)} that calls {@code SafeHtml.sanitize(s.toString(), POLICY)}, with
 * {@code POLICY} a {@code static final} field built once.
 */
public final class HtmlPolicy {

  /**
   * Elements a policy may allow. {@code a} keeps a checked {@code href}, {@code img} a checked
   * {@code src}. The builder throws for any other name.
   */
  public static final Set<String> ELEMENTS =
      Ascii.set(
          "a", "b", "i", "em", "strong", "u", "s", "del", "ins", "sub", "sup", "small", "mark",
          "code", "kbd", "span", "br", "img", "p", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
          "blockquote", "ul", "ol", "li", "div", "hr");

  /**
   * Elements an inline policy ({@link #inlineBuilder()}, for {@link SafeInlineHtml}) may allow:
   * phrasing elements that close nothing open around them in a paragraph, heading, link or button.
   * No {@code a}, no interactive or form content, nothing the parser treats specially. The inline
   * builder throws for any other name.
   */
  public static final Set<String> INLINE_ELEMENTS =
      Ascii.set(
          "b", "i", "em", "strong", "small", "s", "u", "sub", "sup", "code", "kbd", "mark", "abbr", "cite",
          "q", "dfn", "time", "span", "br", "wbr", "img");

  /**
   * Attributes a policy may allow, besides {@code a[href]}, {@code img[src]} and {@code class}
   * ({@link Builder#allowClasses}). Each is kept only on the elements listed here, and only with
   * a value of the stated form: {@code title} on any element and {@code alt} on {@code img} (any
   * text), {@code width}/{@code height} on {@code img} (digits), {@code start} and
   * {@code reversed} on {@code ol}, {@code value} on {@code li} (an integer), and {@code id} on any
   * element ({@code [A-Za-z0-9_-]}, written with the prefix {@value #ID_PREFIX}).
   */
  public static final Set<String> ATTRIBUTES =
      Ascii.set("id", "title", "alt", "width", "height", "start", "reversed", "value");

  /** Link {@code rel} tokens a policy may add. */
  static final Set<String> REL_TOKENS = Ascii.set("nofollow", "noopener", "noreferrer", "ugc");

  /** Written before every kept {@code id}, so user content cannot clobber page ids or globals. */
  static final String ID_PREFIX = "user-content-";

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
   * {@code title}, {@code id} (prefixed {@value #ID_PREFIX}, with {@code href="#x"} links
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

  final Set<String> elements;
  final Set<String> attributes;
  private final Set<String> classNames;
  private final List<String> classPrefixes;
  /** The rel attribute written on a kept link ({@code " rel=\"...\""}), or empty. */
  final String rel;

  final boolean linkTarget;

  /** Built by {@link #inlineBuilder()}: only {@link #INLINE_ELEMENTS}. */
  final boolean inline;

  private HtmlPolicy(Builder b) {
    this.inline = b.inline;
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
    return new Builder(false);
  }

  /**
   * A builder for an inline policy, the only kind {@link SafeInlineHtml#sanitize} takes: as
   * {@link #builder()}, but {@link Builder#allowElements} accepts only {@link #INLINE_ELEMENTS}.
   * As with any policy, allowing {@code img} keeps its {@code src} without
   * {@link Builder#allowAttributes} ({@code src} is not in {@link #ATTRIBUTES}), but only a
   * same-origin path starting with a single {@code /}; an image with any other {@code src} (a
   * relative path such as {@code ../x.gif}, another host, {@code data:}) is dropped.
   */
  public static Builder inlineBuilder() {
    return new Builder(true);
  }

  /** Builds an {@link HtmlPolicy}. Every method rejects names outside the closed sets above. */
  public static final class Builder {
    private final Set<String> elements = new HashSet<>();
    private final Set<String> attributes = new HashSet<>();
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<String> rel = new LinkedHashSet<>(Arrays.asList("nofollow", "noopener"));
    private boolean linkTarget;
    private final boolean inline;

    private Builder(boolean inline) {
      this.inline = inline;
    }

    /**
     * Allows elements from {@link #ELEMENTS} (lower case); any other name throws. {@code li} needs
     * {@code ul} or {@code ol} to be kept.
     */
    public Builder allowElements(String... names) {
      for (String name : names) {
        elements.add(check(name, inline ? INLINE_ELEMENTS : ELEMENTS, inline ? "inline element" : "element"));
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
     * whole class namespace, for CSS overlays and class-initialised widgets. Only tokens of
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
      if (!Ascii.isClassChar(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
