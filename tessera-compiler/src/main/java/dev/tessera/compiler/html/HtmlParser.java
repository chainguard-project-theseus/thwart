package dev.tessera.compiler.html;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.TemplateSource;
import dev.tessera.compiler.model.Document;

/**
 * Parses a template into a {@link Document} using attoparser (non-validating,
 * non-normalizing, HTML-aware for void elements and raw-text elements). Records exact
 * positions for every element, attribute name and attribute value, decodes character
 * references in attribute values ({@code Attribute.value}) while keeping the raw form, and
 * classifies every text node's {@code OutputContext} via {@link Contexts}.
 *
 * <p>Build errors (reported, then parsing continues): unclosed or mismatched non-void elements,
 * duplicate attributes on one element, malformed markup.
 */
public final class HtmlParser {

  public Document parse(TemplateSource source, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#2): HtmlParser.parse");
  }
}
