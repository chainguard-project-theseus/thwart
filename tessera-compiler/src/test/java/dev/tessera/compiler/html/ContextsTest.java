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
    "img, sizes, ATTRIBUTE",
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
    "img, srcset, URL",
    "source, SrcSet, URL",
    "link, imagesrcset, URL",
    "html, manifest, URL",
    "use, xlink:href, URL",
    "area, href, URL",
    "audio, src, URL",
    // SVG animation values (#44): to/from/by are single URLs, values is a ;-separated list (#284)
    "animate, to, URL",
    "set, from, URL",
    "animate, by, URL",
    "animate, values, URL_LIST",
    "SET, VALUES, URL_LIST",
    "div, values, ATTRIBUTE",
    "rect, values, ATTRIBUTE",
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
    "template, #default, EVENT_HANDLER",
    "div, .textcontent, EVENT_HANDLER",
    "div, v-on:click, EVENT_HANDLER",
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
    "div, data-hx-target, ATTRIBUTE",
    // htmx names the framework table does not list are unknown (#330): use data-hx-target
    "div, hx-target, FORBIDDEN",
    "div, hx-swap, FORBIDDEN",
    "div, hx-confirm, FORBIDDEN",
    // htmx request URLs: the response is swapped in as HTML (#249)
    "div, hx-get, RESOURCE_URL",
    "form, hx-post, RESOURCE_URL",
    "button, hx-put, RESOURCE_URL",
    "button, hx-patch, RESOURCE_URL",
    "button, hx-delete, RESOURCE_URL",
    "div, data-hx-get, RESOURCE_URL",
    "div, DATA-HX-POST, RESOURCE_URL",
    "div, HX-Get, RESOURCE_URL",
    "div, sse-connect, RESOURCE_URL",
    "div, ws-connect, RESOURCE_URL",
    "div, data-ws-connect, RESOURCE_URL",
    // htmx history URLs only change the address bar
    "div, hx-sse, FORBIDDEN",
    "div, data-hx-ws, FORBIDDEN",
    "a, hx-push-url, URL",
    "a, hx-replace-url, URL",
    "a, data-hx-push-url, URL",
    // hx-boost decides which form actions htmx requests (#258); bindings of it are directives
    "body, hx-boost, FORBIDDEN",
    "div, data-hx-boost, FORBIDDEN",
    "div, HX-Boost, FORBIDDEN",
    "div, :hx-boost, EVENT_HANDLER",
    "div, v-bind:data-hx-boost, EVENT_HANDLER",
    "div, x-bind:hx-boost, EVENT_HANDLER",
    // a form's action stays URL; the boosted-subtree check is in codegen (#258)
    "form, action, URL",
    "button, formaction, URL",
    "form, :action, EVENT_HANDLER",
    "button, v-bind:formaction, EVENT_HANDLER",
    "form, x-bind:action, EVENT_HANDLER",
    "div, hx-select, FORBIDDEN",
    "div, hx-getter, FORBIDDEN",
    // _hyperscript (#249)
    "button, _, EVENT_HANDLER",
    "div, script, EVENT_HANDLER",
    "div, data-script, EVENT_HANDLER",
    "div, DATA-SCRIPT, EVENT_HANDLER",
    "div, data-scripts, ATTRIBUTE",
    // deny-by-default names (#330): only standard, data-* and custom-element props are ATTRIBUTE
    "div, __, FORBIDDEN",
    "a, _x, FORBIDDEN",
    "div, params, FORBIDDEN",
    "div, xml:lang, FORBIDDEN",
    "div, xmlns:t, FORBIDDEN",
    "div, ngx, FORBIDDEN",
    "div, vue, FORBIDDEN",
    "div, up-on-loaded, FORBIDDEN",
    "div, UP-ON-LOADED, FORBIDDEN",
    "a, up-href, FORBIDDEN",
    "form, hx-action, FORBIDDEN",
    "form, hx-method, FORBIDDEN",
    "div, foo, FORBIDDEN",
    "div, aria-foo, FORBIDDEN",
    "div, data-, FORBIDDEN",
    "div, prop, FORBIDDEN",
    "my-el, my-prop, FORBIDDEN",
    "my-el, prop_x, FORBIDDEN",
    "div, onfoo, EVENT_HANDLER",
    "div, data-x, ATTRIBUTE",
    "div, data-up-on-loaded, ATTRIBUTE",
    "div, role, ATTRIBUTE",
    "div, aria-describedby, ATTRIBUTE",
    "td, bgcolor, ATTRIBUTE",
    "font, color, ATTRIBUTE",
    "font, face, ATTRIBUTE",
    "my-el, prop, ATTRIBUTE",
    "my-el, Prop2, ATTRIBUTE",
    "my-el, title, ATTRIBUTE",
    // until #333, a JSON binding's target is any ordinary name, known or not
    "div, :foo, SCRIPT_ATTRIBUTE",
    // SCRIPT_ATTRIBUTE: Vue/Alpine bindings of an ordinary target take JSON (#246)
    "api-entry, :security, SCRIPT_ATTRIBUTE",
    "div, v-bind:title, SCRIPT_ATTRIBUTE",
    "div, V-Bind:Title, SCRIPT_ATTRIBUTE",
    "div, x-bind:class, SCRIPT_ATTRIBUTE",
    "div, :class, SCRIPT_ATTRIBUTE",
    "div, :data-id, SCRIPT_ATTRIBUTE",
    "div, :aria-label, SCRIPT_ATTRIBUTE",
    "input, :disabled, SCRIPT_ATTRIBUTE",
    "input, :value, SCRIPT_ATTRIBUTE",
    "div, :hx-target, SCRIPT_ATTRIBUTE",
    // ... any other binding stays a directive (EVENT_HANDLER)
    "div, v-bind, EVENT_HANDLER",
    "div, x-bind, EVENT_HANDLER",
    "div, v-bind:, EVENT_HANDLER",
    "div, :, EVENT_HANDLER",
    "div, v-bindx:title, EVENT_HANDLER",
    "div, v-bind.prop, EVENT_HANDLER",
    "div, v-bind:title.prop, EVENT_HANDLER",
    "div, :title.camel, EVENT_HANDLER",
    "div, :title.attr, EVENT_HANDLER",
    "div, x-bind:title.camel, EVENT_HANDLER",
    "div, v-bind:[key], EVENT_HANDLER",
    "div, :[key], EVENT_HANDLER",
    "div, ::title, EVENT_HANDLER",
    "div, :xlink:href, EVENT_HANDLER",
    "div, .title, EVENT_HANDLER",
    "a, :href, EVENT_HANDLER",
    "a, v-bind:href, EVENT_HANDLER",
    "a, x-bind:href, EVENT_HANDLER",
    "img, :src, EVENT_HANDLER",
    "form, :action, EVENT_HANDLER",
    "iframe, :src, EVENT_HANDLER",
    "iframe, :srcdoc, EVENT_HANDLER",
    "object, :data, EVENT_HANDLER",
    "div, :style, EVENT_HANDLER",
    "div, x-bind:style, EVENT_HANDLER",
    "div, :onclick, EVENT_HANDLER",
    "div, :innerHTML, EVENT_HANDLER",
    "div, :outerhtml, EVENT_HANDLER",
    "component, :is, EVENT_HANDLER",
    "my-element, :inner-h-t-m-l, EVENT_HANDLER",
    "a, :protocol, EVENT_HANDLER",
    "area, v-bind:host, EVENT_HANDLER",
    "my-link, :hostname, EVENT_HANDLER",
    "a, :pathname, EVENT_HANDLER",
    "a, :search, EVENT_HANDLER",
    "a, :hash, EVENT_HANDLER",
    "a, :port, EVENT_HANDLER",
    "a, :username, EVENT_HANDLER",
    "a, :password, EVENT_HANDLER",
    "div, :v-html, EVENT_HANDLER",
    "div, :x-data, EVENT_HANDLER",
    "div, :hx-get, EVENT_HANDLER",
    "div, :hx-vals, EVENT_HANDLER",
    "div, :data-bind, EVENT_HANDLER",
    "div, :ng-click, EVENT_HANDLER",
    "div, :_, EVENT_HANDLER",
    "my-component, :params, EVENT_HANDLER",
    "script, :title, EVENT_HANDLER",
    "style, :media, EVENT_HANDLER",
    "meta, :content, EVENT_HANDLER",
    "div, @title, EVENT_HANDLER",
    "div, #title, EVENT_HANDLER",
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

  /** SVG and MathML names are known (#330); a foreign name with a {@code -} is not a custom element. */
  @ParameterizedTest(name = "<{0} {1}> (foreign) -> {2}")
  @CsvSource({
    "path, stroke-dasharray, ATTRIBUTE",
    "path, d, ATTRIBUTE",
    "svg, viewbox, ATTRIBUTE",
    "svg, viewBox, ATTRIBUTE",
    "animate, dur, ATTRIBUTE",
    // Known, but a computed attributeName could retarget the values to CSS.
    "animate, attributename, FORBIDDEN",
    "text, xml:space, ATTRIBUTE",
    "use, xlink:href, URL",
    "mi, mathvariant, ATTRIBUTE",
    "annotation-xml, encoding, ATTRIBUTE",
    "font-face, prop, FORBIDDEN",
    "rect, foo, FORBIDDEN",
  })
  void foreignNames(String element, String attribute, OutputContext expected) {
    Element e = new Element(element, List.of(), List.of(), false, true, POS, true);
    assertEquals(expected, Contexts.attribute(e, attribute));
  }

  /** Only names that fail the known-name rule are unknown; framework and malformed names are not (#330). */
  @Test
  void unknownNames() {
    Element div = element("div");
    assertEquals(true, Contexts.isUnknownName(div, "up-on-loaded"));
    assertEquals(true, Contexts.isUnknownName(div, "hx-action"));
    assertEquals(false, Contexts.isUnknownName(div, "title"));
    assertEquals(false, Contexts.isUnknownName(div, "data-x"));
    assertEquals(false, Contexts.isUnknownName(div, "hx-boost"));
    assertEquals(false, Contexts.isUnknownName(div, "hx-get"));
    assertEquals(false, Contexts.isUnknownName(div, "v-html"));
    assertEquals(false, Contexts.isUnknownName(div, "onfoo"));
    assertEquals(false, Contexts.isUnknownName(div, "a=b"));
    assertEquals(false, Contexts.isUnknownName(element("my-el"), "prop"));
  }

  /** SVG presentation attributes that take a CSS url() are STYLE on a foreign element only. */
  @ParameterizedTest(name = "<{0} {1}> (foreign: {2}) -> {3}")
  @CsvSource({
    "rect, fill, true, STYLE",
    "path, stroke, true, STYLE",
    "g, clip-path, true, STYLE",
    "g, mask, true, STYLE",
    "g, filter, true, STYLE",
    "path, marker-start, true, STYLE",
    "path, marker-mid, true, STYLE",
    "line, marker-end, true, STYLE",
    "path, marker, true, STYLE",
    "svg, cursor, true, STYLE",
    "RECT, FILL, true, STYLE",
    "rect, fill-opacity, true, ATTRIBUTE",
    "rect, stroke-width, true, ATTRIBUTE",
    "rect, width, true, ATTRIBUTE",
    "animate, fill, true, ATTRIBUTE",
    "set, fill, true, ATTRIBUTE",
    "animateMotion, fill, true, ATTRIBUTE",
    "animateTransform, fill, true, ATTRIBUTE",
    "animateColor, fill, true, ATTRIBUTE",
    "animate, stroke, true, STYLE",
    "set, mask, true, STYLE",
    "div, fill, false, ATTRIBUTE",
    "div, cursor, false, ATTRIBUTE",
    "rect, :fill, true, EVENT_HANDLER",
  })
  void svgCssUrlAttributes(String element, String attribute, boolean foreign, OutputContext expected) {
    Element e = new Element(element, List.of(), List.of(), false, true, POS, foreign);
    assertEquals(expected, Contexts.attribute(e, attribute));
  }

  /**
   * The values of an animation whose static attributeName is one of those attributes are STYLE;
   * the attributeName of an animation element cannot be computed.
   */
  @ParameterizedTest(name = "<{0} {1}=\"{2}\"> {3} -> {4}")
  @CsvSource({
    "animate, attributeName, fill, values, STYLE",
    "animate, attributeName, fill, to, STYLE",
    "animate, attributeName, stroke, from, STYLE",
    "animate, attributeName, clip-path, by, STYLE",
    "set, attributeName, mask, to, STYLE",
    "set, attributeName, filter, to, STYLE",
    "set, attributeName, marker-start, to, STYLE",
    "animate, attributeName, marker-mid, values, STYLE",
    "animate, attributeName, marker-end, values, STYLE",
    "set, attributeName, cursor, to, STYLE",
    "set, attributeName, marker, to, STYLE",
    "animateColor, attributeName, fill, values, STYLE",
    "animateTransform, attributeName, fill, values, STYLE",
    "ANIMATE, ATTRIBUTENAME, ' Fill ', VALUES, STYLE",
    "animate, attributeName, svg:fill, values, STYLE",
    "animate, attributeName, fill, fill, ATTRIBUTE",
    "animate, attributeName, fill, begin, ATTRIBUTE",
    "animate, attributeName, href, values, URL_LIST",
    "set, attributeName, xlink:href, to, URL",
    "animate, attributeName, fill-opacity, values, URL_LIST",
    "animate, attributeName, opacity, to, URL",
    "animate, t:attributeName, fill, values, URL_LIST",
    "rect, attributeName, fill, values, ATTRIBUTE",
    "animate, attributeName, fill, attributeName, FORBIDDEN",
    "set, attributeName, fill, ATTRIBUTENAME, FORBIDDEN",
    "animateMotion, attributeName, fill, attributeName, FORBIDDEN",
    "animateTransform, attributeName, fill, attributeName, FORBIDDEN",
    "animateColor, attributeName, fill, attributeName, FORBIDDEN",
    "rect, attributeName, fill, attributeName, ATTRIBUTE",
  })
  void animatedSvgCssUrlValues(String element, String name, String value, String attribute, OutputContext expected) {
    assertEquals(expected, Contexts.attribute(element(element, name, value), attribute));
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
  void metaRefreshContentIsRefresh() {
    assertEquals(
        OutputContext.REFRESH, Contexts.attribute(element("meta", "http-equiv", "refresh"), "content"));
    assertEquals(
        OutputContext.REFRESH, Contexts.attribute(element("META", "HTTP-EQUIV", " Refresh "), "Content"));
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
  void metaRefreshFoldsOnlyAscii() {
    // A browser lower-cases A-Z only: http-equ\u0131v (dotless i) and http-equ\u0130v (dotted capital I) are
    // not http-equiv, and refre\u017Fh (long s) is not refresh, so this content is an ordinary attribute.
    for (String name : List.of("http-equ\u0131v", "http-equ\u0130v")) {
      assertEquals(OutputContext.ATTRIBUTE, Contexts.attribute(element("meta", name, "refresh"), "content"), name);
    }
    assertEquals(
        OutputContext.ATTRIBUTE, Contexts.attribute(element("meta", "http-equiv", "refre\u017Fh"), "content"));
    assertEquals(
        OutputContext.REFRESH, Contexts.attribute(element("meta", "Http-Equiv", "REFRESH"), "content"));
  }

  @Test
  void textContexts() {
    assertEquals(OutputContext.SCRIPT, Contexts.text(element("script")));
    assertEquals(OutputContext.SCRIPT, Contexts.text(element("Script", "src", "/a.js")));
    assertEquals(OutputContext.STYLE, Contexts.text(element("style")));
    assertEquals(OutputContext.STYLE, Contexts.text(element("STYLE")));
    // The verifier names rcdata or text from the parser state (#378).
    assertEquals(OutputContext.TEXT, Contexts.text(element("title")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("TextArea")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("xmp")));
    assertEquals(OutputContext.TEXT, Contexts.text(element("option")));
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
