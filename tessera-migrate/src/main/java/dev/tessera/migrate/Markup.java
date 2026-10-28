package dev.tessera.migrate;

import java.util.Set;
import org.attoparser.MarkupParser;
import org.attoparser.config.ParseConfiguration;

/** The markup parser and HTML element facts that the template passes share. */
final class Markup {
  private Markup() {}

  /**
   * HTML parsing without element balancing, so the handlers see the tags as written. attoparser's
   * {@link MarkupParser} is thread-safe, so one instance serves every pass.
   */
  static final MarkupParser PARSER = new MarkupParser(parseConfiguration());

  /** HTML void elements: they have no end tag. */
  static final Set<String> VOID_ELEMENTS = Compat.setOf("area", "base", "br", "col", "embed", "hr",
      "img", "input", "link", "meta", "param", "source", "track", "wbr");

  private static ParseConfiguration parseConfiguration() {
    ParseConfiguration config = ParseConfiguration.htmlConfiguration();
    config.setElementBalancing(ParseConfiguration.ElementBalancing.NO_BALANCING);
    return config;
  }
}
