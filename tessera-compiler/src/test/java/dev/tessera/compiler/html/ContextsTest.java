package dev.tessera.compiler.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    "img, sizes, FORBIDDEN",
    "div, aria-label, ATTRIBUTE",
    "div, type, FORBIDDEN",
    // URL
    // only object[data] is RESOURCE_URL; object[src] is an ordinary URL attribute
    "object, src, URL",
    "a, href, URL",
    "img, src, URL",
    "form, action, URL",
    "button, formaction, URL",
    "blockquote, cite, URL",
    "video, poster, URL",
    "body, background, FORBIDDEN",
    "img, longdesc, FORBIDDEN",
    "img, usemap, FORBIDDEN",
    "a, ping, FORBIDDEN",
    "img, srcset, FORBIDDEN",
    "source, SrcSet, FORBIDDEN",
    "link, imagesrcset, FORBIDDEN",
    "html, manifest, FORBIDDEN",
    "use, xlink:href, FORBIDDEN",
    "area, href, URL",
    "audio, src, URL",
    // SVG animation values: off the list (no computed value reaches SVG since #678)
    "animate, to, FORBIDDEN",
    "set, from, FORBIDDEN",
    "animate, by, FORBIDDEN",
    "animate, values, FORBIDDEN",
    "SET, VALUES, FORBIDDEN",
    "div, values, FORBIDDEN",
    "rect, values, FORBIDDEN",
    // RESOURCE_URL
    "script, src, RESOURCE_URL",
    "script, href, RESOURCE_URL",
    "script, xlink:href, FORBIDDEN",
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
    // Framework names are ordinary names off the allow-list (#761): FORBIDDEN like any other; names outside
    // [A-Za-z_][A-Za-z0-9_.:-]* (Vue shorthands) are FORBIDDEN
    "div, @click, FORBIDDEN",
    "div, @click.prevent, FORBIDDEN",
    "div, :href, FORBIDDEN",
    "template, #default, FORBIDDEN",
    "div, .textcontent, FORBIDDEN",
    "div, v-on:click, FORBIDDEN",
    "div, v-bind, FORBIDDEN",
    "input, v-model, FORBIDDEN",
    "div, v-if, FORBIDDEN",
    "div, v-else-if, FORBIDDEN",
    "div, v-show, FORBIDDEN",
    "li, v-for, FORBIDDEN",
    "div, v-html, FORBIDDEN",
    "div, V-HTML, FORBIDDEN",
    "div, v-text, FORBIDDEN",
    "template, v-slot:item, FORBIDDEN",
    "div, v-my-directive, FORBIDDEN",
    "div, x-data, FORBIDDEN",
    "div, x-init, FORBIDDEN",
    "div, x-on:click, FORBIDDEN",
    "div, x-html, FORBIDDEN",
    "div, x-show, FORBIDDEN",
    "div, x-effect, FORBIDDEN",
    "div, hx-on, FORBIDDEN",
    "div, hx-on:click, FORBIDDEN",
    "div, hx-on::after-request, FORBIDDEN",
    "div, hx-on-click, FORBIDDEN",
    "div, hx-on--after-request, FORBIDDEN",
    "div, data-hx-on:click, NEUTRAL",
    "div, hx-vals, FORBIDDEN",
    "div, data-hx-vals, NEUTRAL",
    "div, hx-vars, FORBIDDEN",
    "div, hx-headers, FORBIDDEN",
    "div, hx-request, FORBIDDEN",
    "div, hx-trigger, FORBIDDEN",
    "div, ng-click, FORBIDDEN",
    "div, ng-init, FORBIDDEN",
    "div, ng:bind, FORBIDDEN",
    "div, ng_if, FORBIDDEN",
    "div, data-ng-click, NEUTRAL",
    "div, x-ng-click, FORBIDDEN",
    // AngularJS spellings are not modelled either
    "div, data:ng-click, FORBIDDEN",
    "div, data_ng-click, FORBIDDEN",
    "div, x:ng-click, FORBIDDEN",
    "div, x_ng-click, FORBIDDEN",
    "div, DATA:ng-click, FORBIDDEN",
    "div, X_NG_bind, FORBIDDEN",
    "div, data-ng:bind, NEUTRAL",
    "div, :data_ng-click, FORBIDDEN",
    "div, v-bind:x_ng-click, FORBIDDEN",
    "div, data--ng-click, NEUTRAL",
    "div, data-_ng-click, NEUTRAL",
    "div, data-:ng-click, NEUTRAL",
    "div, _ng-click, FORBIDDEN",
    "div, x__ng_bind, FORBIDDEN",
    // AngularJS spellings: off the list too
    "div, data-data-ng-click, NEUTRAL",
    "div, data-xng-click, NEUTRAL",
    "div, data-ng, NEUTRAL",
    "div, data-ngx, NEUTRAL",
    "div, data-bind, NEUTRAL",
    "my-component, params, FORBIDDEN",
    "my-component, PARAMS, FORBIDDEN",
    // data-* names: NEUTRAL
    "div, data-v-1a2b3c, NEUTRAL",
    "div, data-id, NEUTRAL",
    "div, data-action, NEUTRAL",
    "div, data-binding, NEUTRAL",
    "div, data-hx-target, NEUTRAL",
    // any htmx name: off the list (recipe: data-hx-*)
    "div, hx-target, FORBIDDEN",
    "div, hx-swap, FORBIDDEN",
    "div, hx-confirm, FORBIDDEN",
    // htmx request URLs
    "div, hx-get, FORBIDDEN",
    "form, hx-post, FORBIDDEN",
    "button, hx-put, FORBIDDEN",
    "button, hx-patch, FORBIDDEN",
    "button, hx-delete, FORBIDDEN",
    "div, data-hx-get, NEUTRAL",
    "div, DATA-HX-POST, NEUTRAL",
    "div, HX-Get, FORBIDDEN",
    "div, sse-connect, FORBIDDEN",
    "div, ws-connect, FORBIDDEN",
    "div, data-ws-connect, NEUTRAL",
    // htmx history URLs
    "div, hx-sse, FORBIDDEN",
    "div, data-hx-ws, NEUTRAL",
    "a, hx-push-url, FORBIDDEN",
    "a, hx-replace-url, FORBIDDEN",
    "a, data-hx-push-url, NEUTRAL",
    // hx-boost is not modelled (#585)
    "body, hx-boost, FORBIDDEN",
    "div, data-hx-boost, NEUTRAL",
    "div, HX-Boost, FORBIDDEN",
    "div, :hx-boost, FORBIDDEN",
    "div, v-bind:data-hx-boost, FORBIDDEN",
    "div, x-bind:hx-boost, FORBIDDEN",
    // a form's action stays URL; codegen checks a computed one (#585)
    "form, action, URL",
    "button, formaction, URL",
    "form, :action, FORBIDDEN",
    "button, v-bind:formaction, FORBIDDEN",
    "form, x-bind:action, FORBIDDEN",
    "div, hx-select, FORBIDDEN",
    "div, hx-getter, FORBIDDEN",
    // _hyperscript names
    "button, _, FORBIDDEN",
    "div, script, FORBIDDEN",
    "div, data-script, NEUTRAL",
    "div, DATA-SCRIPT, NEUTRAL",
    "div, data-scripts, NEUTRAL",
    // only listed names are accepted; every other plain name is FORBIDDEN, also on a custom element
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
    "div, aria-foo, NEUTRAL",
    "div, data-, NEUTRAL",
    "div, prop, FORBIDDEN",
    "my-el, my-prop, FORBIDDEN",
    "my-el, prop_x, FORBIDDEN",
    "div, onfoo, EVENT_HANDLER",
    "div, data-x, NEUTRAL",
    "div, data-up-on-loaded, NEUTRAL",
    "div, role, ATTRIBUTE",
    "div, aria-describedby, ATTRIBUTE",
    "td, bgcolor, FORBIDDEN",
    "font, color, FORBIDDEN",
    "font, face, FORBIDDEN",
    "font, size, FORBIDDEN",
    "p, color, FORBIDDEN",
    "my-el, prop, FORBIDDEN",
    "my-el, Prop2, FORBIDDEN",
    "my-el, title, ATTRIBUTE",
    // Vue/Alpine bindings: :x is not a plain name, v-bind:x and x-bind:x are off the list
    "div, :foo, FORBIDDEN",
    "api-entry, :security, FORBIDDEN",
    "div, v-bind:title, FORBIDDEN",
    "div, V-Bind:Title, FORBIDDEN",
    "div, x-bind:class, FORBIDDEN",
    "div, :class, FORBIDDEN",
    "div, :data-id, FORBIDDEN",
    "div, :aria-label, FORBIDDEN",
    "input, :disabled, FORBIDDEN",
    "input, :value, FORBIDDEN",
    "div, :hx-target, FORBIDDEN",
    "div, v-bind, FORBIDDEN",
    "div, x-bind, FORBIDDEN",
    "div, v-bind:, FORBIDDEN",
    "div, :, FORBIDDEN",
    "div, v-bindx:title, FORBIDDEN",
    "div, v-bind.prop, FORBIDDEN",
    "div, v-bind:title.prop, FORBIDDEN",
    "div, :title.camel, FORBIDDEN",
    "div, :title.attr, FORBIDDEN",
    "div, x-bind:title.camel, FORBIDDEN",
    "div, v-bind:[key], FORBIDDEN",
    "div, :[key], FORBIDDEN",
    "div, ::title, FORBIDDEN",
    "div, :xlink:href, FORBIDDEN",
    "div, .title, FORBIDDEN",
    "a, :href, FORBIDDEN",
    "a, v-bind:href, FORBIDDEN",
    "a, x-bind:href, FORBIDDEN",
    "img, :src, FORBIDDEN",
    "form, :action, FORBIDDEN",
    "iframe, :src, FORBIDDEN",
    "iframe, :srcdoc, FORBIDDEN",
    "object, :data, FORBIDDEN",
    "div, :style, FORBIDDEN",
    "div, x-bind:style, FORBIDDEN",
    "div, :onclick, FORBIDDEN",
    "div, :innerHTML, FORBIDDEN",
    "div, :outerhtml, FORBIDDEN",
    "component, :is, FORBIDDEN",
    "my-element, :inner-h-t-m-l, FORBIDDEN",
    "a, :protocol, FORBIDDEN",
    "area, v-bind:host, FORBIDDEN",
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
    // The same names elsewhere: for is listed, the others are not.
    "label, for, ATTRIBUTE",
    "div, shadowrootmode, FORBIDDEN",
    "p, charset, FORBIDDEN",
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
   * #761: computed names are one closed allow-list. Every name the migration corpus computes (#760)
   * keeps the context it had; {@code data-*} and an {@code aria-*} name outside WAI-ARIA 1.2 are
   * NEUTRAL; every other plain name is FORBIDDEN on any element, custom elements included, with a
   * fix-it that names a {@code data-*} attribute. Nothing is classified by framework.
   */
  @Test
  void computedNamesAreOneClosedList() {
    for (String n : List.of("class", "value", "title", "disabled", "name", "checked", "id", "lang", "placeholder",
        "selected", "alt", "aria-label", "form", "accept", "colspan", "rowspan", "max", "min", "readonly")) {
      assertEquals(OutputContext.ATTRIBUTE, Contexts.attribute(element("input"), n), n);
    }
    for (String n : List.of("href", "src", "action")) {
      assertEquals(OutputContext.URL, Contexts.attribute(element("a"), n), n);
    }
    assertEquals(OutputContext.STYLE, Contexts.attribute(element("p"), "style"));
    assertEquals(OutputContext.ATTRIBUTE, Contexts.attribute(element("label"), "for"));
    assertEquals(OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("p"), "for"));
    assertEquals(OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("param"), "value"));
    assertEquals(OutputContext.TYPED_ATTRIBUTE, Contexts.attribute(element("a"), "target"));
    assertEquals(OutputContext.REFRESH, Contexts.attribute(element("meta", "http-equiv", "refresh"), "content"));
    for (String n : List.of("data-delay", "data-validation-field", "DATA-X", "data-", "aria-foo")) {
      assertEquals(OutputContext.NEUTRAL, Contexts.attribute(element("div"), n), n);
      assertEquals(OutputContext.NEUTRAL, Contexts.attribute(element("my-el"), n), n);
    }
    assertEquals(OutputContext.ATTRIBUTE, Contexts.attribute(element("my-el"), "title"));
    for (String n : List.of("hx-get", "hx-post", "hx-vals", "x-data", "v-html", "up-on-loaded", "data:foo", "_", "foo",
        "blockid", "icon", "badge", "type", "rel", "loading", "property", "crossorigin", "sandbox", "usemap", "nonce",
        "content", "background", "xml:lang")) {
      assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), n), n);
      assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("my-el"), n), n);
    }
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("button"), "type"));
    assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("div"), "a=b"));
    // A String is not neutral, so data-* alone dead-ends for one: the message names the script prelude too (#782).
    assertEquals("'hx-get' is not on Tessera's list of computed attributes (docs/guide.md section 4.4); write it as a"
        + " static value, or one element per value under t:if; for data your script reads, put a number, boolean or"
        + " link in t:data-hx-get, and any other value in a script declaration such as var x = [[${x}]]",
        Contexts.forbidden(element("div"), "hx-get"));
    assertEquals("Tessera stamps the CSP nonce itself; delete this attribute",
        Contexts.forbidden(element("script"), "nonce"));
    // The specific tree-builder reasons win over the list's.
    assertTrue(Contexts.forbidden(element("input"), "type").startsWith("the type of an <input>"));
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
    // Any other computed content is off the allow-list, named metadata included (#761).
    assertEquals(
        OutputContext.FORBIDDEN, Contexts.attribute(element("meta", "name", "description"), "content"));
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.attribute(element("meta", "http-equiv", "content-language"), "content"));
    assertEquals(
        OutputContext.FORBIDDEN, Contexts.attribute(element("div", "http-equiv", "refresh"), "content"));
  }

  /**
   * #761: a computed {@code <meta>} content is REFRESH under a static {@code http-equiv="refresh"} and
   * off the allow-list otherwise: named metadata (once inert, #590), a policy, a pragma, Open Graph, an
   * unknown name and an unnamed meta alike.
   */
  @ParameterizedTest(name = "<meta {0}> content -> {1}")
  @CsvSource(delimiter = '|', value = {
    "name=description | FORBIDDEN",
    "NAME=Keywords | FORBIDDEN",
    "name=author | FORBIDDEN",
    "name=Application-Name | FORBIDDEN",
    "name=generator | FORBIDDEN",
    "name=color-scheme | FORBIDDEN",
    "name=theme-color | FORBIDDEN",
    "name=description name=keywords | FORBIDDEN",
    "name=author property=og:title | FORBIDDEN",
    "name=description property=al:ios:url | FORBIDDEN",
    "name=description itemprop=url | FORBIDDEN",
    "name=description t:property=x | FORBIDDEN",
    "name=description t:itemprop=x | FORBIDDEN",
    "name=description name=apple-itunes-app | FORBIDDEN",
    "name=apple-itunes-app | FORBIDDEN",
    "name=google-site-verification | FORBIDDEN",
    "name=msapplication-config | FORBIDDEN",
    "name=msapplication-TileImage | FORBIDDEN",
    "name=og:title | FORBIDDEN",
    "name=twitter:image | FORBIDDEN",
    "name=twitter:title | FORBIDDEN",
    "name=refresh | FORBIDDEN",
    "name=robots | FORBIDDEN",
    "name=q | FORBIDDEN",
    "name= | FORBIDDEN",
    "name=description-x | FORBIDDEN",
    // Names and values fold A-Z only: a look-alike is not on the allow-list.
    "name=descr\u0131ption | FORBIDDEN",
    "name=KEYWORD\u017F | FORBIDDEN",
    "NAME=description PROPERTY=og:url | FORBIDDEN",
    "name=description ItemProp=url | FORBIDDEN",
    "name=referrer | FORBIDDEN",
    "Name=REFERRER | FORBIDDEN",
    "name=viewport | FORBIDDEN",
    "name=HTMX-Config | FORBIDDEN",
    "name=description http-equiv=default-style | FORBIDDEN",
    "http-equiv=content-security-policy | FORBIDDEN",
    "http-equiv=default-style | FORBIDDEN",
    "property=og:title | FORBIDDEN",
    "itemprop=name | FORBIDDEN",
    "t:name=description | FORBIDDEN",
    "| FORBIDDEN",
  })
  void otherMetaContentIsOffTheList(String attrs, OutputContext expected) {
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
        OutputContext.FORBIDDEN, Contexts.attribute(element("meta", "name", " Viewport "), "content"));
  }

  @Test
  void metaRefreshFoldsOnlyAscii() {
    // A browser lower-cases A-Z only: http-equ\u0131v (dotless i) and http-equ\u0130v (dotted capital I) are
    // not http-equiv, and refre\u017Fh (long s) is not refresh, so this content is not REFRESH (#761: off the list).
    for (String name : List.of("http-equ\u0131v", "http-equ\u0130v")) {
      assertEquals(OutputContext.FORBIDDEN, Contexts.attribute(element("meta", name, "refresh"), "content"), name);
    }
    assertEquals(
        OutputContext.FORBIDDEN, Contexts.attribute(element("meta", "http-equiv", "refre\u017Fh"), "content"));
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
    // Other http-equiv values, and content on another element, are off the allow-list (#761).
    assertEquals(
        OutputContext.FORBIDDEN,
        Contexts.attribute(element("meta", "http-equiv", "content-language"), "content"));
    assertEquals(
        OutputContext.FORBIDDEN,
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
