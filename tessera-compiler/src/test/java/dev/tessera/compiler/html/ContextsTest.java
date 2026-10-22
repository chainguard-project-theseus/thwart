package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.OutputContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContextsTest {

  private static final SourcePos POS = new SourcePos("t.html", 1, 1);

  private static Element element(String name, String... attrs) {
    List<Attribute> list = new ArrayList<>();
    for (int i = 0; i < attrs.length; i += 2) {
      list.add(new Attribute(attrs[i], attrs[i + 1], attrs[i + 1], '"', POS, POS));
    }
    return new Element(name, list, List.of(), false, true, POS);
  }

  @ParameterizedTest(name = "<{0} {1}> -> {2}")
  @CsvSource({
    // ATTRIBUTE
    "div, class, ATTRIBUTE",
    "div, id, ATTRIBUTE",
    "input, value, ATTRIBUTE",
    "div, data-id, ATTRIBUTE",
    "div, title, ATTRIBUTE",
    "img, alt, ATTRIBUTE",
    "img, srcset, ATTRIBUTE",
    "a, _x, ATTRIBUTE",
    "div, xmlns:t, ATTRIBUTE",
    "div, aria-label, ATTRIBUTE",
    "object, src, URL",
    // URL
    "a, href, URL",
    "img, src, URL",
    "form, action, URL",
    "button, formaction, URL",
    "blockquote, cite, URL",
    "video, poster, URL",
    "body, background, URL",
    "img, longdesc, URL",
    "img, usemap, URL",
    "a, ping, URL",
    "use, xlink:href, URL",
    "area, href, URL",
    "audio, src, URL",
    // RESOURCE_URL
    "script, src, RESOURCE_URL",
    "script, href, RESOURCE_URL",
    "script, xlink:href, RESOURCE_URL",
    "iframe, src, RESOURCE_URL",
    "frame, src, RESOURCE_URL",
    "embed, src, RESOURCE_URL",
    "object, data, RESOURCE_URL",
    "base, href, RESOURCE_URL",
    "link, href, RESOURCE_URL",
    // case-insensitive
    "A, HREF, URL",
    "SCRIPT, Src, RESOURCE_URL",
    "IFrame, SRC, RESOURCE_URL",
    "Link, Href, RESOURCE_URL",
    "DIV, CLASS, ATTRIBUTE",
    "div, STYLE, STYLE",
    "div, OnClick, EVENT_HANDLER",
    "iframe, SrcDoc, FORBIDDEN",
    // STYLE
    "div, style, STYLE",
    "span, style, STYLE",
    // EVENT_HANDLER
    "div, onclick, EVENT_HANDLER",
    "body, onload, EVENT_HANDLER",
    "img, onerror, EVENT_HANDLER",
    "a, onmouseover, EVENT_HANDLER",
    "svg, onbegin, EVENT_HANDLER",
    "x, on, EVENT_HANDLER",
    // FORBIDDEN
    "iframe, srcdoc, FORBIDDEN",
    "div, srcdoc, FORBIDDEN",
    "div, @click, FORBIDDEN",
    "div, :href, FORBIDDEN",
    "div, [href], FORBIDDEN",
    "div, (click), FORBIDDEN",
    "div, 1abc, FORBIDDEN",
    "div, -x, FORBIDDEN",
    "div, a=b, FORBIDDEN",
    "div, a b, FORBIDDEN",
    "div, \"x, FORBIDDEN",
    "div, a/b, FORBIDDEN",
    "div, <x, FORBIDDEN",
  })
  void attributeTable(String element, String attribute, OutputContext expected) {
    assertEquals(expected, Contexts.attribute(element(element), attribute));
  }

  @Test
  void attributeOddNames() {
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), ""));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), "a\u0000b"));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), "a\tb"));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), "é"));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), null));
  }

  @Test
  void attributeWithoutElement() {
    assertEquals(OutputContext.URL, Contexts.attribute(null, "src"));
    assertEquals(OutputContext.ATTRIBUTE, Contexts.attribute(null, "class"));
  }

  @ParameterizedTest(name = "<script type=\"{0}\"> -> {1}")
  @CsvSource({
    "'', SCRIPT",
    "text/javascript, SCRIPT",
    "TEXT/JavaScript, SCRIPT",
    "' text/javascript ', SCRIPT",
    "application/javascript, SCRIPT",
    "text/ecmascript, SCRIPT",
    "application/ecmascript, SCRIPT",
    "module, SCRIPT",
    "Module, SCRIPT",
    "application/json, SCRIPT",
    "application/ld+json, SCRIPT",
    "text/html, FORBIDDEN",
    "text/template, FORBIDDEN",
    "text/x-handlebars-template, FORBIDDEN",
    "importmap, FORBIDDEN",
    "speculationrules, FORBIDDEN",
    "text/plain, FORBIDDEN",
    "text/vbscript, FORBIDDEN",
    "text/javascript; charset=utf-8, FORBIDDEN",
  })
  void scriptTypes(String type, OutputContext expected) {
    assertEquals(expected, Contexts.text(element("script", "type", type)));
    assertEquals(expected, Contexts.text(element("SCRIPT", "TYPE", type)));
  }

  @Test
  void textContexts() {
    assertEquals(OutputContext.TEXT, Contexts.text(null));
    assertEquals(OutputContext.SCRIPT, Contexts.text(element("script")));
    assertEquals(OutputContext.SCRIPT, Contexts.text(element("Script", "src", "/a.js")));
    assertEquals(OutputContext.STYLE, Contexts.text(element("style")));
    assertEquals(OutputContext.STYLE, Contexts.text(element("STYLE")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("title")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("textarea")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("div")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("t:block")));
  }

  @Test
  void valuelessScriptTypeIsJavaScript() {
    Element script =
        new Element(
            "script",
            List.of(new Attribute("type", null, null, (char) 0, POS, null)),
            List.of(),
            false,
            true,
            POS);
    assertEquals(OutputContext.SCRIPT, Contexts.text(script));
  }

  @Test
  void dynamicScriptTypeIsForbidden() {
    assertEquals(OutputContext.FORBIDDEN, Contexts.text(element("script", "t:type", "${t}")));
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.text(element("script", "type", "text/javascript", "t:type", "${t}")));
  }
}
