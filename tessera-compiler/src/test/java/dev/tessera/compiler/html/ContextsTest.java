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
      String value = attrs[i + 1];
      list.add(
          value == null
              ? new Attribute(attrs[i], null, null, (char) 0, POS, null)
              : new Attribute(attrs[i], value, value, '"', POS, POS));
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
    "div, type, ATTRIBUTE",
    // URL
    // only object[data] is RESOURCE_URL; object[src] is an ordinary URL attribute
    "object, src, URL",
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
    // EVENT_HANDLER: framework directives evaluated as JavaScript (#235)
    "div, @click, EVENT_HANDLER",
    "div, @click.prevent, EVENT_HANDLER",
    "div, :href, EVENT_HANDLER",
    "api-entry, :security, EVENT_HANDLER",
    "template, #default, EVENT_HANDLER",
    "div, .textcontent, EVENT_HANDLER",
    "div, v-on:click, EVENT_HANDLER",
    "div, v-bind:title, EVENT_HANDLER",
    "div, v-bind, EVENT_HANDLER",
    "input, v-model, EVENT_HANDLER",
    "div, v-if, EVENT_HANDLER",
    "div, v-else-if, EVENT_HANDLER",
    "div, v-show, EVENT_HANDLER",
    "li, v-for, EVENT_HANDLER",
    "div, v-html, EVENT_HANDLER",
    "div, V-HTML, EVENT_HANDLER",
    "div, v-text, EVENT_HANDLER",
    "template, v-slot:item, EVENT_HANDLER",
    "div, v-my-directive, EVENT_HANDLER",
    "div, x-data, EVENT_HANDLER",
    "div, x-init, EVENT_HANDLER",
    "div, x-on:click, EVENT_HANDLER",
    "div, x-bind:class, EVENT_HANDLER",
    "div, x-html, EVENT_HANDLER",
    "div, x-show, EVENT_HANDLER",
    "div, x-effect, EVENT_HANDLER",
    "div, hx-on, EVENT_HANDLER",
    "div, hx-on:click, EVENT_HANDLER",
    "div, hx-on::after-request, EVENT_HANDLER",
    "div, hx-on-click, EVENT_HANDLER",
    "div, hx-on--after-request, EVENT_HANDLER",
    "div, data-hx-on:click, EVENT_HANDLER",
    "div, hx-vals, EVENT_HANDLER",
    "div, data-hx-vals, EVENT_HANDLER",
    "div, hx-vars, EVENT_HANDLER",
    "div, hx-headers, EVENT_HANDLER",
    "div, hx-request, EVENT_HANDLER",
    "div, hx-trigger, EVENT_HANDLER",
    "div, ng-click, EVENT_HANDLER",
    "div, ng-init, EVENT_HANDLER",
    "div, ng:bind, EVENT_HANDLER",
    "div, ng_if, EVENT_HANDLER",
    "div, data-ng-click, EVENT_HANDLER",
    "div, x-ng-click, EVENT_HANDLER",
    "div, data-bind, EVENT_HANDLER",
    "my-component, params, EVENT_HANDLER",
    "my-component, PARAMS, EVENT_HANDLER",
    // data-only and string-valued framework attributes stay ordinary attributes
    "div, data-v-1a2b3c, ATTRIBUTE",
    "div, data-id, ATTRIBUTE",
    "div, data-action, ATTRIBUTE",
    "div, data-binding, ATTRIBUTE",
    "div, hx-target, ATTRIBUTE",
    "div, hx-swap, ATTRIBUTE",
    "div, hx-confirm, ATTRIBUTE",
    "div, hx-get, ATTRIBUTE",
    "div, params, ATTRIBUTE",
    "div, xml:lang, ATTRIBUTE",
    "div, ngx, ATTRIBUTE",
    "div, vue, ATTRIBUTE",
    // FORBIDDEN
    "iframe, srcdoc, FORBIDDEN",
    "div, srcdoc, FORBIDDEN",
    "div, [href], FORBIDDEN",
    "div, (click), FORBIDDEN",
    "div, 1abc, FORBIDDEN",
    "div, -x, FORBIDDEN",
    "div, a=b, FORBIDDEN",
    "div, a b, FORBIDDEN",
    "div, \"x, FORBIDDEN",
    "div, a/b, FORBIDDEN",
    "div, <x, FORBIDDEN",
    "script, type, FORBIDDEN",
    "SCRIPT, TYPE, FORBIDDEN",
    "meta, http-equiv, FORBIDDEN",
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
  void metaRefreshContentIsForbidden() {
    assertEquals(
        OutputContext.FORBIDDEN, Contexts.attribute(element("meta", "http-equiv", "refresh"), "content"));
    assertEquals(
        OutputContext.FORBIDDEN, Contexts.attribute(element("META", "HTTP-EQUIV", " Refresh "), "Content"));
    // Other meta content is an ordinary attribute.
    assertEquals(
        OutputContext.ATTRIBUTE, Contexts.attribute(element("meta", "name", "description"), "content"));
    assertEquals(
        OutputContext.ATTRIBUTE,
        Contexts.attribute(element("meta", "http-equiv", "content-language"), "content"));
    assertEquals(
        OutputContext.ATTRIBUTE, Contexts.attribute(element("div", "http-equiv", "refresh"), "content"));
  }

  @Test
  void textContexts() {
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
    assertEquals(OutputContext.SCRIPT, Contexts.text(element("script", "type", null)));
  }

  @Test
  void dynamicScriptTypeIsForbidden() {
    assertEquals(OutputContext.FORBIDDEN, Contexts.text(element("script", "t:type", "${t}")));
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.text(element("script", "type", "text/javascript", "t:type", "${t}")));
  }
}
