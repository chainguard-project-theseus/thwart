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
    "div, data-id, NEUTRAL",
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
    "img, usemap, TYPED_ATTRIBUTE",
    "a, ping, FORBIDDEN",
    "img, srcset, FORBIDDEN",
    "source, SrcSet, FORBIDDEN",
    "link, imagesrcset, FORBIDDEN",
    "html, manifest, URL",
    "use, xlink:href, URL",
    "area, href, URL",
    "audio, src, URL",
    // SVG animation values: standard and not inert (no computed value reaches SVG since #678)
    "animate, to, TYPED_ATTRIBUTE",
    "set, from, TYPED_ATTRIBUTE",
    "animate, by, TYPED_ATTRIBUTE",
    "animate, values, TYPED_ATTRIBUTE",
    "SET, VALUES, TYPED_ATTRIBUTE",
    "div, values, TYPED_ATTRIBUTE",
    "rect, values, TYPED_ATTRIBUTE",
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
    // NEUTRAL (#583 rule 3, #584): framework names are ordinary non-standard names; names outside
    // [A-Za-z_][A-Za-z0-9_.:-]* (Vue shorthands) are FORBIDDEN
    "div, @click, FORBIDDEN",
    "div, @click.prevent, FORBIDDEN",
    "div, :href, FORBIDDEN",
    "template, #default, FORBIDDEN",
    "div, .textcontent, FORBIDDEN",
    "div, v-on:click, NEUTRAL",
    "div, v-bind, NEUTRAL",
    "input, v-model, NEUTRAL",
    "div, v-if, NEUTRAL",
    "div, v-else-if, NEUTRAL",
    "div, v-show, NEUTRAL",
    "li, v-for, NEUTRAL",
    "div, v-html, NEUTRAL",
    "div, V-HTML, NEUTRAL",
    "div, v-text, NEUTRAL",
    "template, v-slot:item, NEUTRAL",
    "div, v-my-directive, NEUTRAL",
    "div, x-data, NEUTRAL",
    "div, x-init, NEUTRAL",
    "div, x-on:click, NEUTRAL",
    "div, x-html, NEUTRAL",
    "div, x-show, NEUTRAL",
    "div, x-effect, NEUTRAL",
    "div, hx-on, NEUTRAL",
    "div, hx-on:click, NEUTRAL",
    "div, hx-on::after-request, NEUTRAL",
    "div, hx-on-click, NEUTRAL",
    "div, hx-on--after-request, NEUTRAL",
    "div, data-hx-on:click, NEUTRAL",
    "div, hx-vals, NEUTRAL",
    "div, data-hx-vals, NEUTRAL",
    "div, hx-vars, NEUTRAL",
    "div, hx-headers, NEUTRAL",
    "div, hx-request, NEUTRAL",
    "div, hx-trigger, NEUTRAL",
    "div, ng-click, NEUTRAL",
    "div, ng-init, NEUTRAL",
    "div, ng:bind, NEUTRAL",
    "div, ng_if, NEUTRAL",
    "div, data-ng-click, NEUTRAL",
    "div, x-ng-click, NEUTRAL",
    // AngularJS spellings are not modelled either
    "div, data:ng-click, NEUTRAL",
    "div, data_ng-click, NEUTRAL",
    "div, x:ng-click, NEUTRAL",
    "div, x_ng-click, NEUTRAL",
    "div, DATA:ng-click, NEUTRAL",
    "div, X_NG_bind, NEUTRAL",
    "div, data-ng:bind, NEUTRAL",
    "div, :data_ng-click, FORBIDDEN",
    "div, v-bind:x_ng-click, NEUTRAL",
    "div, data--ng-click, NEUTRAL",
    "div, data-_ng-click, NEUTRAL",
    "div, data-:ng-click, NEUTRAL",
    "div, _ng-click, NEUTRAL",
    "div, x__ng_bind, NEUTRAL",
    // a name AngularJS may read is no longer denied by its spelling (#584)
    "div, data-data-ng-click, NEUTRAL",
    "div, data-xng-click, NEUTRAL",
    "div, data-ng, NEUTRAL",
    "div, data-ngx, NEUTRAL",
    "div, data-bind, NEUTRAL",
    "my-component, params, NEUTRAL",
    "my-component, PARAMS, NEUTRAL",
    // data-* names
    "div, data-v-1a2b3c, NEUTRAL",
    "div, data-id, NEUTRAL",
    "div, data-action, NEUTRAL",
    "div, data-binding, NEUTRAL",
    "div, data-hx-target, NEUTRAL",
    // any htmx name
    "div, hx-target, NEUTRAL",
    "div, hx-swap, NEUTRAL",
    "div, hx-confirm, NEUTRAL",
    // htmx request URLs: a computed one is a neutral same-origin path
    "div, hx-get, NEUTRAL",
    "form, hx-post, NEUTRAL",
    "button, hx-put, NEUTRAL",
    "button, hx-patch, NEUTRAL",
    "button, hx-delete, NEUTRAL",
    "div, data-hx-get, NEUTRAL",
    "div, DATA-HX-POST, NEUTRAL",
    "div, HX-Get, NEUTRAL",
    "div, sse-connect, NEUTRAL",
    "div, ws-connect, NEUTRAL",
    "div, data-ws-connect, NEUTRAL",
    // htmx history URLs
    "div, hx-sse, NEUTRAL",
    "div, data-hx-ws, NEUTRAL",
    "a, hx-push-url, NEUTRAL",
    "a, hx-replace-url, NEUTRAL",
    "a, data-hx-push-url, NEUTRAL",
    // hx-boost is not modelled (#585): NEUTRAL like any other non-HTML name (#584)
    "body, hx-boost, NEUTRAL",
    "div, data-hx-boost, NEUTRAL",
    "div, HX-Boost, NEUTRAL",
    "div, :hx-boost, FORBIDDEN",
    "div, v-bind:data-hx-boost, NEUTRAL",
    "div, x-bind:hx-boost, NEUTRAL",
    // a form's action stays URL; codegen checks a computed one (#585)
    "form, action, URL",
    "button, formaction, URL",
    "form, :action, FORBIDDEN",
    "button, v-bind:formaction, NEUTRAL",
    "form, x-bind:action, NEUTRAL",
    "div, hx-select, NEUTRAL",
    "div, hx-getter, NEUTRAL",
    // _hyperscript names
    "button, _, NEUTRAL",
    "div, script, NEUTRAL",
    "div, data-script, NEUTRAL",
    "div, DATA-SCRIPT, NEUTRAL",
    "div, data-scripts, NEUTRAL",
    // only standard names are ATTRIBUTE; every other plain name is NEUTRAL, also on a custom element
    "div, __, NEUTRAL",
    "a, _x, NEUTRAL",
    "div, params, NEUTRAL",
    "div, xml:lang, NEUTRAL",
    "div, xmlns:t, FORBIDDEN",
    "div, ngx, NEUTRAL",
    "div, vue, NEUTRAL",
    "div, up-on-loaded, NEUTRAL",
    "div, UP-ON-LOADED, NEUTRAL",
    "a, up-href, NEUTRAL",
    "form, hx-action, NEUTRAL",
    "form, hx-method, NEUTRAL",
    "div, foo, NEUTRAL",
    "div, aria-foo, NEUTRAL",
    "div, data-, NEUTRAL",
    "div, prop, NEUTRAL",
    "my-el, my-prop, NEUTRAL",
    "my-el, prop_x, NEUTRAL",
    "div, onfoo, EVENT_HANDLER",
    "div, data-x, NEUTRAL",
    "div, data-up-on-loaded, NEUTRAL",
    "div, role, ATTRIBUTE",
    "div, aria-describedby, ATTRIBUTE",
    "td, bgcolor, ATTRIBUTE",
    "font, color, FORBIDDEN",
    "font, face, FORBIDDEN",
    "font, size, FORBIDDEN",
    "p, color, TYPED_ATTRIBUTE",
    "my-el, prop, NEUTRAL",
    "my-el, Prop2, NEUTRAL",
    "my-el, title, ATTRIBUTE",
    // Vue/Alpine bindings: :x is FORBIDDEN (not a plain name), v-bind:x and x-bind:x NEUTRAL
    "div, :foo, FORBIDDEN",
    "api-entry, :security, FORBIDDEN",
    "div, v-bind:title, NEUTRAL",
    "div, V-Bind:Title, NEUTRAL",
    "div, x-bind:class, NEUTRAL",
    "div, :class, FORBIDDEN",
    "div, :data-id, FORBIDDEN",
    "div, :aria-label, FORBIDDEN",
    "input, :disabled, FORBIDDEN",
    "input, :value, FORBIDDEN",
    "div, :hx-target, FORBIDDEN",
    "div, v-bind, NEUTRAL",
    "div, x-bind, NEUTRAL",
    "div, v-bind:, NEUTRAL",
    "div, :, FORBIDDEN",
    "div, v-bindx:title, NEUTRAL",
    "div, v-bind.prop, NEUTRAL",
    "div, v-bind:title.prop, NEUTRAL",
    "div, :title.camel, FORBIDDEN",
    "div, :title.attr, FORBIDDEN",
    "div, x-bind:title.camel, NEUTRAL",
    "div, v-bind:[key], FORBIDDEN",
    "div, :[key], FORBIDDEN",
    "div, ::title, FORBIDDEN",
    "div, :xlink:href, FORBIDDEN",
    "div, .title, FORBIDDEN",
    "a, :href, FORBIDDEN",
    "a, v-bind:href, NEUTRAL",
    "a, x-bind:href, NEUTRAL",
    "img, :src, FORBIDDEN",
    "form, :action, FORBIDDEN",
    "iframe, :src, FORBIDDEN",
    "iframe, :srcdoc, FORBIDDEN",
    "object, :data, FORBIDDEN",
    "div, :style, FORBIDDEN",
    "div, x-bind:style, NEUTRAL",
    "div, :onclick, FORBIDDEN",
    "div, :innerHTML, FORBIDDEN",
    "div, :outerhtml, FORBIDDEN",
    "component, :is, FORBIDDEN",
    "my-element, :inner-h-t-m-l, FORBIDDEN",
    "a, :protocol, FORBIDDEN",
    "area, v-bind:host, NEUTRAL",
    "my-link, :hostname, FORBIDDEN",
    "a, :pathname, FORBIDDEN",
    "a, :search, FORBIDDEN",
    "a, :hash, FORBIDDEN",
    "a, :port, FORBIDDEN",
    "a, :username, FORBIDDEN",
    "a, :password, FORBIDDEN",
    "div, :v-html, FORBIDDEN",
    "div, :x-data, FORBIDDEN",
    "div, :hx-get, FORBIDDEN",
    "div, :hx-vals, FORBIDDEN",
    "div, :data-bind, FORBIDDEN",
    "div, :ng-click, FORBIDDEN",
    "div, :_, FORBIDDEN",
    "my-component, :params, FORBIDDEN",
    "script, :title, FORBIDDEN",
    "style, :media, FORBIDDEN",
    "meta, :content, FORBIDDEN",
    "div, @title, FORBIDDEN",
    "div, #title, FORBIDDEN",
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
    "input, type, FORBIDDEN",
    "INPUT, Type, FORBIDDEN",
    "meta, charset, FORBIDDEN",
    "p, encoding, FORBIDDEN",
    "div, xmlns:foo, FORBIDDEN",
    "meta, http-equiv, FORBIDDEN",
    "p, is, FORBIDDEN",
    "P, IS, FORBIDDEN",
    "svg, xmlns, FORBIDDEN",
    "svg, xmlns:xlink, FORBIDDEN",
    "div, xmlns, FORBIDDEN",
    "template, shadowrootmode, FORBIDDEN",
    "template, shadowrootclonable, FORBIDDEN",
    "template, shadowrootdelegatesfocus, FORBIDDEN",
    "TEMPLATE, ShadowRootMode, FORBIDDEN",
    "template, for, FORBIDDEN",
    "meta, charset, FORBIDDEN",
    "META, CharSet, FORBIDDEN",
    // The same names elsewhere are ordinary attributes.
    "label, for, ATTRIBUTE",
    "div, shadowrootmode, TYPED_ATTRIBUTE",
    "p, charset, TYPED_ATTRIBUTE",
  })
  void attributeTable(String element, String attribute, OutputContext expected) {
    assertEquals(expected, Contexts.attribute(element(element), attribute));
  }

  /** #678: every attribute of an SVG or MathML element is FORBIDDEN computed, whatever its name. */
  @ParameterizedTest(name = "<{0} {1}> (foreign) -> {2}")
  @CsvSource({
    "path, stroke-dasharray, FORBIDDEN",
    "path, d, FORBIDDEN",
    "svg, viewbox, FORBIDDEN",
    "svg, viewBox, FORBIDDEN",
    "animate, dur, FORBIDDEN",
    "text, xml:space, FORBIDDEN",
    "use, xlink:href, FORBIDDEN",
    "mi, mathvariant, FORBIDDEN",
    "annotation-xml, encoding, FORBIDDEN",
    "font-face, prop, FORBIDDEN",
    "rect, foo, FORBIDDEN",
  })
  void foreignNames(String element, String attribute, OutputContext expected) {
    Element e = new Element(element, List.of(), List.of(), false, true, POS, true);
    assertEquals(expected, Contexts.attribute(e, attribute));
  }

  /**
   * Every plain name that is not standard is NEUTRAL (#583 rule 3, #584), on any element; nothing
   * is classified by framework. Malformed names stay FORBIDDEN.
   */
  @Test
  void nonStandardNamesAreNeutral() {
    for (String name : List.of("up-on-loaded", "hx-action", "data:foo", "data_ngx", "x_ng-click", "data-x", "hx-get",
        "v-html", "x-data", "data-bind", "_", "foo", "v-bind:title")) {
      assertEquals(OutputContext.NEUTRAL, Contexts.attribute(element("div"), name), name);
      assertEquals(OutputContext.NEUTRAL, Contexts.attribute(element("my-el"), name), name);
    }
    assertEquals(OutputContext.ATTRIBUTE, Contexts.attribute(element("my-el"), "title"));
    assertEquals(OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("my-el"), "target"));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), "a=b"));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), ":title"));
    assertEquals(OutputContext.NEUTRAL, Contexts.attribute(element("div"), "hx-ws"));
  }

  /** A static is names a custom element or nothing (HTML 4.13.3; #272). */
  @Test
  void customElementNames() {
    for (String ok : List.of("my-button", "a-", "x.y-z_1", "a-b-c")) {
      assertEquals(true, Contexts.isCustomElementName(ok), ok);
    }
    for (String bad : List.of("script", "iframe", "vue:script", "My-button", "-a", "1-a", "font-face",
        "annotation-xml", "", "a-é", "xı-y", "a b-c")) {
      assertEquals(false, Contexts.isCustomElementName(bad), bad);
    }
    assertEquals(false, Contexts.isCustomElementName(null));
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
    // Named metadata is an ordinary attribute; any other content takes only a safe value (#539).
    assertEquals(
        OutputContext.ATTRIBUTE, Contexts.attribute(element("meta", "name", "description"), "content"));
    assertEquals(
        OutputContext.TYPED_ATTRIBUTE,
        Contexts.attribute(element("meta", "http-equiv", "content-language"), "content"));
    assertEquals(
        OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("div", "http-equiv", "refresh"), "content"));
  }

  /**
   * #539, #590: the content of a {@code <meta>} is inert text only under a static name on the
   * allow-list and without http-equiv, property or itemprop; a policy, a pragma, a name a browser,
   * OS or crawler acts on, an unknown name or an unnamed meta takes only literals and numbers.
   */
  @ParameterizedTest(name = "<meta {0}> content -> {1}")
  @CsvSource(delimiter = '|', value = {
    "name=description | ATTRIBUTE",
    "NAME=Keywords | ATTRIBUTE",
    "name=author | ATTRIBUTE",
    "name=Application-Name | ATTRIBUTE",
    "name=generator | ATTRIBUTE",
    "name=color-scheme | ATTRIBUTE",
    "name=theme-color | ATTRIBUTE",
    "name=description name=keywords | ATTRIBUTE",
    "name=author property=og:title | TYPED_ATTRIBUTE",
    "name=description property=al:ios:url | TYPED_ATTRIBUTE",
    "name=description itemprop=url | TYPED_ATTRIBUTE",
    "name=description t:property=x | TYPED_ATTRIBUTE",
    "name=description t:itemprop=x | TYPED_ATTRIBUTE",
    "name=description name=apple-itunes-app | TYPED_ATTRIBUTE",
    "name=apple-itunes-app | TYPED_ATTRIBUTE",
    "name=google-site-verification | TYPED_ATTRIBUTE",
    "name=msapplication-config | TYPED_ATTRIBUTE",
    "name=msapplication-TileImage | TYPED_ATTRIBUTE",
    "name=og:title | TYPED_ATTRIBUTE",
    "name=twitter:image | TYPED_ATTRIBUTE",
    "name=twitter:title | TYPED_ATTRIBUTE",
    "name=refresh | TYPED_ATTRIBUTE",
    "name=robots | TYPED_ATTRIBUTE",
    "name=q | TYPED_ATTRIBUTE",
    "name= | TYPED_ATTRIBUTE",
    "name=description-x | TYPED_ATTRIBUTE",
    // Names and values fold A-Z only: a look-alike is not on the allow-list.
    "name=descr\u0131ption | TYPED_ATTRIBUTE",
    "name=KEYWORD\u017F | TYPED_ATTRIBUTE",
    "NAME=description PROPERTY=og:url | TYPED_ATTRIBUTE",
    "name=description ItemProp=url | TYPED_ATTRIBUTE",
    "name=referrer | TYPED_ATTRIBUTE",
    "Name=REFERRER | TYPED_ATTRIBUTE",
    "name=viewport | TYPED_ATTRIBUTE",
    "name=HTMX-Config | TYPED_ATTRIBUTE",
    "name=description http-equiv=default-style | TYPED_ATTRIBUTE",
    "http-equiv=content-security-policy | TYPED_ATTRIBUTE",
    "http-equiv=default-style | TYPED_ATTRIBUTE",
    "property=og:title | TYPED_ATTRIBUTE",
    "itemprop=name | TYPED_ATTRIBUTE",
    "t:name=description | TYPED_ATTRIBUTE",
    "| TYPED_ATTRIBUTE",
  })
  void metaContentIsInertOnlyAsNamedMetadata(String attrs, OutputContext expected) {
    List<String> kv = new ArrayList<>();
    if (attrs != null) {
      for (String a : attrs.trim().split(" ")) {
        kv.add(a.substring(0, a.indexOf('=')));
        kv.add(a.substring(a.indexOf('=') + 1));
      }
    }
    assertEquals(expected, Contexts.attribute(element("meta", kv.toArray(new String[0])), "content"));
    // A name with spaces around it, as a browser trims it.
    assertEquals(
        OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("meta", "name", " Viewport "), "content"));
  }

  @Test
  void metaRefreshFoldsOnlyAscii() {
    // A browser lower-cases A-Z only: http-equ\u0131v (dotless i) and http-equ\u0130v (dotted capital I) are
    // not http-equiv, and refre\u017Fh (long s) is not refresh, so this content is not REFRESH (#539: TYPED).
    for (String name : List.of("http-equ\u0131v", "http-equ\u0130v")) {
      assertEquals(OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("meta", name, "refresh"), "content"), name);
    }
    assertEquals(
        OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("meta", "http-equiv", "refre\u017Fh"), "content"));
    assertEquals(
        OutputContext.REFRESH, Contexts.attribute(element("meta", "Http-Equiv", "REFRESH"), "content"));
  }

  @Test
  void metaContentTypeContentIsForbidden() {
    // The tree builder reads it for the page's encoding.
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.attribute(element("meta", "http-equiv", "Content-Type"), "content"));
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.attribute(element("META", "HTTP-EQUIV", " content-TYPE "), "Content"));
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.attribute(element("meta", "content", "text/html", "http-equiv", "content-type"), "content"));
    // Other http-equiv values, and the same attributes on another element, take a safe value (#539).
    assertEquals(
        OutputContext.TYPED_ATTRIBUTE,
        Contexts.attribute(element("meta", "http-equiv", "content-language"), "content"));
    assertEquals(
        OutputContext.TYPED_ATTRIBUTE,
        Contexts.attribute(element("div", "http-equiv", "Content-Type"), "content"));
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

  /**
   * A browser reads the first of two {@code type} attributes, as {@code CsrfInjector.isStaticPost}
   * reads the first {@code method} (#591). The parser rejects a duplicate attribute, so no template
   * gets here; the context table still agrees with a browser.
   */
  @Test
  void firstStaticScriptTypeDecides() {
    Element js = element("script", "type", "text/javascript", "type", "application/json");
    assertEquals(OutputContext.SCRIPT, Contexts.text(js));
    assertEquals(false, Contexts.isJsonScript(js));
    assertEquals(true, Contexts.isJsonScript(element("script", "type", "application/json", "TYPE", "module")));
    assertEquals(OutputContext.FORBIDDEN, Contexts.text(element("script", "type", "text/plain", "type", "module")));
    assertEquals(OutputContext.SCRIPT, Contexts.text(element("script", "type", "module", "type", "text/plain")));
  }

  @Test
  void dynamicScriptTypeIsForbidden() {
    assertEquals(OutputContext.FORBIDDEN, Contexts.text(element("script", "t:type", "${t}")));
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.text(element("script", "type", "text/javascript", "t:type", "${t}")));
  }
}
