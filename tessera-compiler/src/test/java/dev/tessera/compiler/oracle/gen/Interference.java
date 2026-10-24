package dev.tessera.compiler.oracle.gen;

/**
 * One value of the static-interference axis (#321): static markup written right before the hole
 * (and, for the smuggling forms, right after it) that a compiler may read differently from a
 * browser. For a script or style hole it goes inside the code, right around {@code [[s]]}; for a
 * {@code t:text}/{@code t:utext} hole, around the element that carries it.
 *
 * <p>The order of the constants is part of the enumeration (append new values at the end).
 */
public enum Interference {
  NONE("none", "", ""),

  // Comments, CDATA and bogus comments.
  EMPTY_COMMENT("<!-->", "<!-->", ""),
  OPEN_COMMENT("<!--", "<!--", ""),
  CLOSE_COMMENT("-->", "-->", ""),
  CDATA_OPEN("<![CDATA[ >", "<![CDATA[ > ", ""),
  PROCESSING_INSTRUCTION("<?x>", "<?x>", ""),
  BOGUS_END_TAG("</ x>", "</ x>", ""),
  BOGUS_DECLARATION("<!x>", "<!x>", ""),

  // Quotes.
  STRAY_QUOTE_IN_TAG("<p title=a\">", "<p title=a\">", ""),
  OPEN_ATTRIBUTE_QUOTE("<img title=\"", "<img title=\"", ""),
  DOUBLE_QUOTE("\"", "\"", ""),
  SINGLE_QUOTE("'", "'", ""),

  // Character references (A4: decoded inside SVG script, not inside HTML script).
  QUOT_REFERENCES("&quot;..&quot;", "&quot;", "&quot;"),
  LT_REFERENCE("&lt;", "&lt;", ""),
  NUMERIC_REFERENCES("&#60;script&#62;", "&#60;script&#62;", ""),
  AMBIGUOUS_AMPERSAND("&amp", "&amp", ""),

  // Script comment starts.
  BLOCK_COMMENT_START("/*", "/*", ""),
  LINE_COMMENT_START("//", "//", ""),

  // Breakout and end tags.
  SVG_END("</svg>", "</svg>", ""),
  P_START("<p>", "<p>", ""),
  B_START("<b>", "<b>", ""),
  FONT_COLOR("<font color=red>", "<font color=red>", ""),
  SCRIPT_END_UNCLOSED("</script", "</script", ""),
  TITLE_END("</title>", "</title>", ""),
  TEXTAREA_END("</textarea>", "</textarea>", ""),
  NOSCRIPT_END("</noscript>", "</noscript>", ""),
  LT_GT("<..>", "<", ">"),
  MUSTACHE("{{..}}", "{{ ", " }}"),

  // Non-HTML whitespace in a tag name and between attributes (#338).
  TEXTAREA_VT("<textarea\\u000B>", "<textarea\u000B>", "</textarea>"),
  TEXTAREA_US("<textarea\\u001F>", "<textarea\u001F>", "</textarea>"),
  TEXTAREA_LS("<textarea\\u2028>", "<textarea\u2028>", "</textarea>"),
  TEXTAREA_IDEOGRAPHIC_SPACE("<textarea\\u3000>", "<textarea\u3000>", "</textarea>"),
  ATTRIBUTES_VT("<textarea a=\"b\"\\u000Bc=\"d\">", "<textarea a=\"b\"\u000Bc=\"d\">", "</textarea>"),
  ATTRIBUTES_IDEOGRAPHIC_SPACE(
      "<textarea a=\"b\"\\u3000c=\"d\">", "<textarea a=\"b\"\u3000c=\"d\">", "</textarea>"),

  // Smuggling: static markup that hides a script start from one parser but not the other.
  /** A14 (#295): {@code <!-->} ends the comment at once in a browser. */
  COMMENT_HIDDEN_SCRIPT("<!--> <script> -->", "<!--> <script> -->\n", "\n<!-- </script> -->"),
  /** A13 (#297): a quote in an unquoted value. */
  QUOTE_HIDDEN_SCRIPT("<p title=a\"><script>//\">", "<p title=a\"><script>//\">\n", "\n//</p><!-- </script> -->"),
  /** #298: an unquoted value ending in {@code /} does not self-close a textarea. */
  UNQUOTED_SLASH_TEXTAREA(
      "<textarea x=y/>",
      "<textarea x=y/><a title=\"</textarea><script>//\">\n",
      "\n//</a><!-- </script> -->"),
  /** A10 (#296): a CDATA section is a bogus comment in HTML content, ended by the first {@code >}. */
  CDATA_HIDDEN_SCRIPT("<![CDATA[ > <script>//]]>", "<![CDATA[ > <script>//]]>", "<!-- </script> -->"),
  /** B0 (#301): {@code </svg>} inside an SVG script comment ends the SVG in a browser. */
  SVG_SCRIPT_BREAKOUT("/*</svg><img src=x title=*/..//>", "/*</svg><img src=x title=*/", "//>");

  private final String label;
  private final String prefix;
  private final String suffix;

  Interference(String label, String prefix, String suffix) {
    this.label = label;
    this.prefix = prefix;
    this.suffix = suffix;
  }

  /** The label used in case names (non-printing characters written as {@code \}{@code uXXXX}). */
  public String label() {
    return label;
  }

  /** The static text written before the hole. */
  public String prefix() {
    return prefix;
  }

  /** The static text written after the hole (empty for most values). */
  public String suffix() {
    return suffix;
  }
}
