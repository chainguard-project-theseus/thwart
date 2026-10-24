package dev.tessera.compiler.oracle.gen;

/**
 * One value of the hole axis (#321): where the value goes. The value is the template parameter
 * {@code String s}, or {@code SafeHtml h} for raw output.
 *
 * <p>Placement depends on the {@link Kind}:
 *
 * <ul>
 *   <li>{@code INLINE}: the hole is written in the innermost container's text.
 *   <li>{@code ON_CONTAINER}: the {@code t:} attribute goes on the innermost container ({@code
 *       <title t:text="s">}); a {@code span} carries it when there is no container.
 *   <li>{@code CARRIER}: a child element carries the {@code t:} attribute ({@code <a t:href="s">}).
 *   <li>{@code SCRIPT}/{@code STYLE}: code around {@code [[s]]}, wrapped in a {@code <script>}
 *       ({@code <style>}) unless the innermost container already is one.
 * </ul>
 *
 * <p>The order of the constants is part of the enumeration (append new values at the end).
 */
public enum Hole {
  TEXT_INLINE("[[s]]", Kind.INLINE, "[[s]]", ""),
  SAFE_HTML_INLINE("[(h)]", Kind.INLINE, "[(h)]", ""),
  T_TEXT("t:text", Kind.ON_CONTAINER, " t:text=\"s\"", ""),
  T_UTEXT("t:utext", Kind.ON_CONTAINER, " t:utext=\"h\"", ""),
  ATTR_TITLE("t:title", Kind.CARRIER, "<span t:title=\"s\">x</span>", ""),
  ATTR_HREF("t:href", Kind.CARRIER, "<a t:href=\"s\">x</a>", ""),
  ATTR_LINK("t:href=@{..}", Kind.CARRIER, "<a t:href=\"@{/p/{x}(x=s, q=s)}\">x</a>", ""),
  ATTR_SRC("t:src", Kind.CARRIER, "<img t:src=\"s\">", ""),
  ATTR_ACTION("t:action", Kind.CARRIER, "<form t:action=\"s\"></form>", ""),
  ATTR_FORMACTION("t:formaction", Kind.CARRIER, "<button t:formaction=\"s\">x</button>", ""),
  ATTR_DATA("t:data-x", Kind.CARRIER, "<span t:data-x=\"s\">x</span>", ""),
  ATTR_VALUE("t:value", Kind.CARRIER, "<input t:value=\"s\">", ""),
  ATTR_CLASS("t:class", Kind.CARRIER, "<span t:class=\"s\">x</span>", ""),
  ATTR_T_ATTR("t:attr", Kind.CARRIER, "<span t:attr=\"x=${s}\">x</span>", ""),
  ATTR_STYLE("t:style", Kind.CARRIER, "<span t:style=\"s\">x</span>", ""),
  SCRIPT_BARE("script:[[s]]", Kind.SCRIPT, "", ""),
  SCRIPT_VAR("script:var a = [[s]];", Kind.SCRIPT, "var a = ", ";"),
  SCRIPT_DOUBLE_QUOTED("script:\"[[s]]\"", Kind.SCRIPT, "var a = \"", "\";"),
  SCRIPT_SINGLE_QUOTED("script:'[[s]]'", Kind.SCRIPT, "var a = '", "';"),
  SCRIPT_TEMPLATE_LITERAL("script:`[[s]]`", Kind.SCRIPT, "var a = `", "`;"),
  SCRIPT_TEMPLATE_SUBSTITUTION("script:`${[[s]]}`", Kind.SCRIPT, "var a = `${", "}`;"),
  SCRIPT_BLOCK_COMMENT("script:/*[[s]]*/", Kind.SCRIPT, "/*", "*/"),
  SCRIPT_LINE_COMMENT("script:// [[s]]", Kind.SCRIPT, "// ", "\n"),
  SCRIPT_AFTER_DIVISION("script:a / b; [[s]]", Kind.SCRIPT, "var c = a / b; var d = ", ";"),
  SCRIPT_AFTER_REGEX("script:x\\n/\"/.test(y); [[s]]", Kind.SCRIPT, "var r = x\n/\"/.test(y); var z = ", ";"),
  STYLE_BLOCK("style:a{color:[[s]]}", Kind.STYLE, "a { color: ", "; }");

  /** How a hole is placed; see the class Javadoc. */
  public enum Kind {
    INLINE,
    ON_CONTAINER,
    CARRIER,
    SCRIPT,
    STYLE
  }

  private final String label;
  private final Kind kind;
  private final String markup;
  private final String after;

  Hole(String label, Kind kind, String markup, String after) {
    this.label = label;
    this.kind = kind;
    this.markup = markup;
    this.after = after;
  }

  /** The label used in case names. */
  public String label() {
    return label;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * For {@code INLINE} and {@code CARRIER}: the markup; for {@code ON_CONTAINER}: the attribute
   * (with a leading space); for {@code SCRIPT}/{@code STYLE}: the code before {@code [[s]]}.
   */
  public String markup() {
    return markup;
  }

  /** For {@code SCRIPT}/{@code STYLE}: the code after {@code [[s]]}; empty otherwise. */
  public String after() {
    return after;
  }

  /** Whether the hole's value is the {@code SafeHtml h} parameter rather than {@code String s}. */
  public boolean safeHtml() {
    return this == SAFE_HTML_INLINE || this == T_UTEXT;
  }
}
