package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Single-file conversion rules, byte preservation and the unsupported-construct audit. */
class MigratorTest {

  private static Migrator.Result migrate(String source) throws Exception {
    return Migrator.migrate("t.html", source);
  }

  private static String convert(String source) throws Exception {
    Migrator.Result result = migrate(source);
    assertEquals(result.output(), migrate(result.output()).output(), "not idempotent");
    return result.output();
  }

  private static List<String> messages(String source) throws Exception {
    return migrate(source).items().stream().map(i -> i.line() + ": " + i.message()).toList();
  }

  /** Asserts the report messages for {@code src}, in order: each starts with its {@code expected}. */
  private static void assertMessages(String src, String... expected) throws Exception {
    List<String> messages = messages(src);
    assertEquals(expected.length, messages.size(), messages.toString());
    for (int i = 0; i < expected.length; i++) {
      assertTrue(messages.get(i).startsWith(expected[i]), messages.toString());
    }
  }

  // ---- source preservation ----

  @Test
  void bytesOutsideRewrittenAttributesStayIdentical() throws Exception {
    String in = "<!DOCTYPE html>\r\n<!-- th:text=\"${x}\" -->\r\n<HTML  lang=en\tclass='a'>\r\n"
        + "<body\n  data-x = \"1\"  hidden>\r\n<![CDATA[ ${y} ]]>\r\n"
        + "<script>if (a < b && c) { x = [1, 2]; }</script>\r\n"
        + "<p   th:text = \"'hi'\"   >&amp; text ${z} </p></body></HTML>\r\n";
    String expected = in.replace("th:text = \"'hi'\"", "t:text = \"'hi'\"");
    assertEquals(expected, convert(in));
  }

  @Test
  void templateWithoutThymeleafIsUnchanged() throws Exception {
    String in = "<html><body><p class=\"x\">plain</p></body></html>\n";
    assertEquals(in, convert(in));
    assertEquals(List.of(), messages(in));
  }

  @Test
  void tagSelectorsKeepHyphensAndTBlock() throws Exception {
    // ~{::my-card} and ~{::th:block} select by tag name (#137); <th:block> is <t:block>.
    assertEquals("<div t:replace=\"~{layout :: layout(~{::my-card}, ~{::t:block})}\">x</div>",
        convert("<div th:replace=\"~{layout :: layout(~{::my-card}, ~{::th:block})}\">x</div>"));
  }

  @Test
  void prototypeOnlyCommentMarkersAreDropped() throws Exception {
    // Thymeleaf renders the content of <!--/*/ ... /*/-->; Tessera rejects the markers (#132).
    assertEquals("<p t:params=\"Object x\"> <b t:text=\"x\">y</b> </p>\n<!--/* a */-->",
        convert("<p><!--/*/ <b th:text=\"${x}\">y</b> /*/--></p>\n<!--/* a */-->"));
    assertEquals("<!--/*/ x -->", convert("<!--/*/ x -->"));
  }

  // ---- attribute and element names ----

  @Test
  void addsAMissingSpaceBetweenAttributes() throws Exception {
    String src = "<a href=\"/a\"class=\"x\" title='t'id=\"i\" data-x=y lang=\"en\">a</a>\n";
    assertEquals("<a href=\"/a\" class=\"x\" title='t' id=\"i\" data-x=y lang=\"en\">a</a>\n", convert(src));
    assertMessages(src, "1: no space before attribute `class`", "1: no space before attribute `id`");
    assertTrue(messages(src).get(0).endsWith(": a space was added, which a browser reads the same"));
    String thymeleaf = "<a t:params=\"String x\" th:href=\"@{/a}\"th:text=\"${x}\">a</a>";
    assertEquals("<a t:params=\"String x\" t:href=\"@{/a}\" t:text=\"x\">a</a>", convert(thymeleaf));
    // Negative: a space, a line break, or an unquoted value before the next attribute.
    for (String ok : List.of("<a href=\"/a\" class=\"x\">a</a><b title=\"t\"\n>b</b>\n", "<b title=\"t\"\nid=\"i\">b</b>")) {
      assertEquals(ok, convert(ok));
      assertMessages(ok);
    }
  }

  @Test
  void unknownComputedNameIsReportedWithTheDataFixIt() throws Exception {
    String src = "<div t:params=\"String x\"><a th:up-on-loaded=\"${x}\" th:items=\"${x}\" t:foo=\"x\">a</a></div>";
    assertEquals("<div t:params=\"String x\"><a t:up-on-loaded=\"x\" t:items=\"x\" t:foo=\"x\">a</a></div>", convert(src));
    assertMessages(src,
        "1: `th:up-on-loaded`: `up-on-loaded` is not a known attribute, and a computed attribute must be a standard"
            + " HTML, SVG, MathML or ARIA attribute, a `data-*` attribute or a plain prop on a custom element (a build"
            + " error in Tessera); if it is data for your script, write `th:data-up-on-loaded`; if a framework reads it,"
            + " write it as a static attribute",
        "1: `th:items`: `items` is not a known attribute",
        "1: `t:foo`: `foo` is not a known attribute");
    String attr = "<div t:params=\"String x\"><a th:attr=\"up-on-loaded=${x},title=${x}\">a</a></div>";
    assertEquals("<div t:params=\"String x\"><a t:attr=\"up-on-loaded=x,title=x\">a</a></div>", convert(attr));
    assertMessages(attr, "1: `up-on-loaded` in `th:attr` is not a known attribute, and a computed attribute must be a"
        + " standard HTML, SVG, MathML or ARIA attribute, a `data-*` attribute or a plain prop on a custom element (a"
        + " build error in Tessera); if it is data for your script, write `data-up-on-loaded`");
  }

  @Test
  void htmxNameThatHtmxAlsoReadsWithDataIsRewritten() throws Exception {
    String src = "<div t:params=\"String x\"><a th:hx-target=\"${x}\" th:HX-Swap=\"'innerHTML'\" t:hx-confirm=\"x\">a</a></div>";
    assertEquals("<div t:params=\"String x\"><a t:data-hx-target=\"x\" t:data-HX-Swap=\"'innerHTML'\""
        + " t:data-hx-confirm=\"x\">a</a></div>", convert(src));
    assertMessages(src,
        "1: `th:hx-target` was rewritten to `t:data-hx-target`: `hx-target` is not a known attribute, which is a build"
            + " error for a computed attribute in Tessera, and htmx reads `data-hx-target` the same way",
        "1: `th:HX-Swap` was rewritten to `t:data-HX-Swap`",
        "1: `t:hx-confirm` was rewritten to `t:data-hx-confirm`");
    String attr = "<div t:params=\"String x\"><a th:attr=\"hx-target=${x}, hx-select = ${x}\">a</a></div>";
    assertEquals("<div t:params=\"String x\"><a t:attr=\"data-hx-target=x, data-hx-select = x\">a</a></div>", convert(attr));
    assertMessages(attr, "1: `hx-target` in `th:attr` was rewritten to `data-hx-target`: `hx-target` is not a known"
        + " attribute, which is a build error for a computed attribute in Tessera, and htmx reads `data-hx-target` the"
        + " same way", "1: `hx-select` in `th:attr` was rewritten to `data-hx-select`");
  }

  @Test
  void knownAndFrameworkComputedNamesAreNotReported() throws Exception {
    // Standard names, data-*, processors, a custom element's props, and the framework names the
    // compiler classifies itself (an htmx script attribute is its own build error).
    String src = "<div t:params=\"String x\"><a th:href=\"@{/a}\" th:aria-label=\"${x}\" th:data-hx-target=\"${x}\""
        + " th:text=\"${x}\" th:classappend=\"${x}\" th:v-bind:title=\"${x}\" th:hx-get=\"@{/a}\" th:hx-vals=\"${x}\""
        + " th:x-text=\"${x}\">a</a><my-list th:items=\"${x}\" th:params=\"${x}\"></my-list>"
        + "<svg><path th:d=\"${x}\"/></svg></div>";
    convert(src);
    assertMessages(src);
    // An unknown name the compiler rejects for another reason first is left to it.
    String odd = "<div t:params=\"String x\"><a th:attr=\"1a=${x}\" th:hx-ext=\"${x}\">a</a></div>";
    assertMessages(odd, "1: `th:hx-ext`: `hx-ext` is not a known attribute");
  }

  @Test
  void angularDirectiveSpellingsAreLeftToTheCompiler() throws Exception {
    // AngularJS's directiveNormalize reads each of these as ngClick (or ngInit, ngBind); the
    // compiler rejects them as framework directives, not as unknown names.
    for (String name : List.of("data:ng-click", "data_ng-click", "x:ng-click", "x_ng-click", "data--ng-click",
        "_ng-click", "DATA:Ng-Click", "X_NG_init", "x_-ng:bind", "data-_ng-click")) {
      assertTrue(ConversionRules.angularDirective(name.toLowerCase(Locale.ROOT)), name);
      String src = "<div t:params=\"String x\"><a th:" + name + "=\"${x}\" th:attr=\"" + name + "=${x}\">a</a></div>";
      assertEquals("<div t:params=\"String x\"><a t:" + name + "=\"x\" t:attr=\"" + name + "=x\">a</a></div>",
          convert(src));
      assertMessages(src);
    }
    // The coarse deny (ignoring - _ :, a name starting with ng, xng or datang) covers more than
    // AngularJS reads; other data-* names stay ordinary, and other names are unknown.
    for (String name : List.of("data_ngx", "data-ngx", "data-ng", "xng-click", "ng")) {
      assertTrue(ConversionRules.angularDirective(name), name);
    }
    for (String name : List.of("data:foo", "data-data-ng-click", "data-xng-click", "x-xng", "data-name")) {
      assertFalse(ConversionRules.angularDirective(name), name);
    }
    String other = "<div t:params=\"String x\"><a th:data:foo=\"${x}\" th:data-data-ng-click=\"${x}\">a</a></div>";
    assertMessages(other,
        "1: `th:data:foo`: `data:foo` is not a known attribute, and a computed attribute must be a standard HTML,"
            + " SVG, MathML or ARIA attribute, a `data-*` attribute or a plain prop on a custom element (a build error"
            + " in Tessera); if it is data for your script, write `th:data-data:foo`");
  }


  @Test
  void renamesNamespaceAttributesAndBlocks() throws Exception {
    assertEquals(
        "<html xmlns:t=\"https://tessera.dev\"><t:block t:if=\"ok\">x</t:block><t:block t:insert=\"f\"/></html>",
        convert("<html xmlns:th=\"http://www.thymeleaf.org\"><th:block th:if=\"${ok}\">x</th:block>"
            + "<th:block th:insert=\"${f}\"/></html>")
            .replace(" t:params=\"Object ok, Object f\"", ""));
  }

  @Test
  void literalValuedAttributesAreOnlyRenamed() throws Exception {
    assertEquals("<div><p t:remove=\"all\" t:errorclass=\"error\">x</p></div>",
        convert("<div><p th:remove=\"all\" th:errorclass=\"error\">x</p></div>"));
  }

  // ---- expressions ----

  @Test
  void dropsRedundantDollarWrappers() throws Exception {
    assertEquals("<p t:params=\"Object a, Object b\"><i t:text=\"a.b\"></i><i t:text=\"(a + b) * 2\"></i>"
            + "<i t:text=\"a + ' ' + b\"></i></p>",
        convert("<p><i th:text=\"${a.b}\"></i><i th:text=\"${a + b} * 2\"></i>"
            + "<i th:text=\"${a} + ' ' + ${b}\"></i></p>"));
  }

  @Test
  void keepsDollarInsideStringLiteralsAndSubstitutions() throws Exception {
    assertEquals("<p t:params=\"Object name\"><i t:text=\"'${not.me}'\"></i><i t:text=\"|Hi ${name}!|\"></i></p>",
        convert("<p><i th:text=\"'${not.me}'\"></i><i th:text=\"|Hi ${name}!|\"></i></p>"));
  }

  @Test
  void rewritesUtilityObjects() throws Exception {
    assertEquals("<p t:params=\"Object xs, Object a, Object b, Object s, Object d\">"
            + "<i t:if=\"xs.isEmpty()\"></i><i t:if=\"not (a + b).isEmpty()\"></i>"
            + "<i t:text=\"xs.size()\"></i><i t:text=\"strings.trim(s) + temporals.format(d, 'y')\"></i></p>",
        convert("<p><i th:if=\"${#lists.isEmpty(xs)}\"></i><i th:if=\"${not #lists.isEmpty(a + b)}\"></i>"
            + "<i th:text=\"${#lists.size(xs)}\"></i>"
            + "<i th:text=\"${#strings.trim(s) + #temporals.format(d, 'y')}\"></i></p>"));
  }

  @Test
  void eachWithAndAttrRewriteEachExpression() throws Exception {
    assertEquals("<ul t:params=\"Object items, Object b\"><li t:each=\"it, st : items\" t:with=\"x=it.a, y=b\""
            + " t:attr=\"data-i=st.index\" t:text=\"x + y + st.count\"></li></ul>",
        convert("<ul><li th:each=\"it, st : ${items}\" th:with=\"x=${it.a}, y=${b}\""
            + " th:attr=\"data-i=${st.index}\" th:text=\"${x + y + st.count}\"></li></ul>"));
  }

  @Test
  void quotesUnquotedValuesThatNeedIt() throws Exception {
    assertEquals("<div t:fragment=\"card(Object a, Object b)\">x</div>",
        convert("<div th:fragment=card(a,b)>x</div>"));
  }

  @Test
  void requotesSingleQuotedValuesWhenTheRewriteAddsAQuote() throws Exception {
    assertEquals("<div><p t:class=\"'nav-item'\" t:if=\"fields.hasErrors('*')\">x</p></div>",
        convert("<div><p th:class='nav-item' th:if='${#fields.hasAnyErrors()}'>x</p></div>"));
  }

  @Test
  void singleQuotedValuesWithoutAddedQuotesStaySingleQuoted() throws Exception {
    assertEquals("<p t:params=\"Object a\"><i t:text='a'></i></p>",
        convert("<p><i th:text='${a}'></i></p>"));
  }

  @Test
  void singleQuotedValueHoldingDoubleQuoteIsNotEscaped() throws Exception {
    // Left for the compiler to reject rather than silently escaped.
    assertEquals("<div t:params=\"Object x\"><p t:if='fields.hasErrors('*') or \"x\"'>x</p></div>",
        migrate("<div><p th:if='${#fields.hasAnyErrors() or \"x\"}'>x</p></div>").output());
  }

  // ---- fragments and params ----

  @Test
  void typesFragmentParametersAsObjectAndReportsThem() throws Exception {
    String src = "<div>\n<p th:fragment=\"card (owner, List<Pet> pets)\" th:text=\"${owner.name + pets}\">x</p></div>";
    assertEquals("<div>\n<p t:fragment=\"card(Object owner, List<Pet> pets)\" t:text=\"owner.name + pets\">x</p></div>",
        convert(src));
    assertEquals(List.of("2: fragment `card` parameters declared as `Object` (`owner`); replace each with its type"
        + " (`Fragment` for markup arguments such as `~{::body}`)"), messages(src));
  }

  @Test
  void parameterlessFragmentIsUnchanged() throws Exception {
    assertEquals("<div t:fragment=\"footer\">x</div>", convert("<div th:fragment=\"footer\">x</div>"));
  }

  @Test
  void insertsParamsStubForRootVariables() throws Exception {
    String src = "<html xmlns:th=\"http://www.thymeleaf.org\">\n<body th:object=\"${form}\">"
        + "<a th:href=\"@{/o/{id}(id=${owner.id}, q=${query})}\" th:text=\"#{greet(${user.name})}\">x</a>"
        + "<b th:text=\"*{name}\"></b><i th:replace=\"~{frag :: item(${owner}, title=${t})}\"></i>"
        + "<i th:insert=\"other/page :: part\"></i><p>[[${count}]] [(${html})]</p></body></html>";
    assertEquals("<html xmlns:t=\"https://tessera.dev\""
            + " t:params=\"Object form, Object owner, Object query, Object user, Object t, Object count, Object html\">",
        convert(src).lines().findFirst().orElseThrow());
    assertMessages(src, "2: link `@{/o/{id}(id=owner.id, q=query)}` fails the render if a path variable is `null`",
        "2: `[(...)]` prints `html` without escaping",
        "1: `form` is declared as `Object`", "1: `owner` is declared as `Object`",
        "1: `query` is declared as `Object`", "1: `user` is declared as `Object`",
        "1: `t` is declared as `Object`", "1: `count` is declared as `Object`",
        "1: `html` is declared as `Object`");
  }

  // ---- raw output ----

  @Test
  void rawOutputIsReportedWithTheSanitizeRecipe() throws Exception {
    String src = "<div t:params=\"Article a\"><p th:utext=\"${a.body}\"></p>\n<p>[(${a.bio})]</p></div>";
    assertEquals("<div t:params=\"Article a\"><p t:utext=\"a.body\"></p>\n<p>[(${a.bio})]</p></div>", convert(src));
    assertEquals(List.of(
            "1: " + String.format(ConversionRules.UTEXT_MESSAGE, "th:utext", "a.body", "a.body"),
            "2: " + String.format(ConversionRules.UTEXT_MESSAGE, "[(...)]", "a.bio", "a.bio")),
        messages(src));
  }

  @Test
  void messagesAndSanitizedOutputAreNotReported() throws Exception {
    String src = "<div t:params=\"Article a\"><p th:utext=\"#{intro(${a.title})}\"></p>"
        + "<p th:utext=\"${html.sanitize(a.body)}\"></p><p t:utext=\"a.body\"></p>"
        + "<p>[(${html.sanitizeRich(a.bio)})]</p></div>";
    assertEquals(List.of(), messages(src));
  }

  @Test
  void existingParamsAreKept() throws Exception {
    String in = "<html t:params=\"OwnerView owner\"><p th:text=\"${owner.name}\">x</p></html>";
    assertEquals("<html t:params=\"OwnerView owner\"><p t:text=\"owner.name\">x</p></html>", convert(in));
    assertEquals(List.of(), messages(in));
  }

  @Test
  void importIsNotAnExpression() throws Exception {
    String src = "<html t:import=\"com.example.OwnerView\"><p th:text=\"${a}\">x</p></html>";
    assertEquals("<html t:import=\"com.example.OwnerView\" t:params=\"Object a\"><p t:text=\"a\">x</p></html>",
        convert(src));
    assertEquals(List.of("1: " + String.format(ConversionRules.PARAM_STUB_MESSAGE, "a")), messages(src));
  }

  @Test
  void nestedParamsAddNoRoots() throws Exception {
    String in = "<div><div t:params=\"List<Pet> pets\">x</div></div>";
    assertEquals(in, convert(in));
    assertEquals(List.of(), messages(in));
  }

  @Test
  void loopAndWithVariablesAreNotParams() throws Exception {
    assertEquals("<ul t:params=\"Object pets\"><li t:each=\"p : pets\" t:with=\"n=p.name\" t:text=\"n + pStat.index\"></li></ul>",
        convert("<ul><li th:each=\"p : ${pets}\" th:with=\"n=${p.name}\" th:text=\"${n + pStat.index}\"></li></ul>"));
  }

  // ---- unsupported constructs: left in place and reported (negative tests) ----

  @Test
  void staticClassAccessIsLeftInPlaceAndReported() throws Exception {
    String src = "<p th:text=\"${T(java.lang.Runtime).getRuntime().exec('id')}\">x</p>";
    assertEquals("<p t:text=\"${T(java.lang.Runtime).getRuntime().exec('id')}\">x</p>", convert(src));
    assertEquals(List.of("1: `T(...)` static class access is not supported (never); compute the value in the"
        + " controller, add it to the model and declare it in `t:params`, or call a static `@TemplateFunction`"),
        messages(src));
  }

  @Test
  void preprocessingIsLeftInPlaceAndReported() throws Exception {
    String src = "<div>\n\n<input th:field=\"*{__${name}__}\"/><a th:href=\"@{/x/__${i}__}\">x</a>"
        + "<a th:href=\"@{'/x?p=__${i}__&q=1'}\">y</a></div>";
    assertEquals(src.replace("th:", "t:"), convert(src));
    assertMessages(src,
        "3: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per"
            + " field (a typed alternative is tracked in #43)",
        "3: `__...__` preprocessing is not supported",
        "3: `__...__` preprocessing is not supported",
        "3: link with a non-literal path");
  }

  // ---- mechanical rewrites found by the petclinic port (#42) ----

  @Test
  void stringBuiltLinksWithOneQueryParameterAreRewritten() throws Exception {
    String src = "<p><a th:href=\"@{'/owners?page=' + ${i}}\">a</a>"
        + "<a th:href=\"@{ '/owners?page=__${n + 1}__' }\">b</a>"
        + "<a th:href=\"@{'/a/b_c?page_no=' + ${p.no}}\">c</a></p>";
    assertEquals("<p t:params=\"Object i, Object n, Object p\"><a t:href=\"@{/owners(page=i)}\">a</a>"
            + "<a t:href=\"@{/owners(page=(n + 1))}\">b</a><a t:href=\"@{/a/b_c(page_no=p.no)}\">c</a></p>",
        convert(src));
    assertMessages(src, // only the params stub
        "1: `i` is declared as `Object`", "1: `n` is declared as `Object`", "1: `p` is declared as `Object`");
  }

  @Test
  void stringBuiltLinksThatAreNotOnePathAndOneParameterAreReported() throws Exception {
    for (String link : List.of("@{'/o?a=' + ${x} + '&b=' + ${y}}", "@{'/o/' + ${id}}", "@{'o?a=' + ${x}}",
        "@{'/o?a=' + ${x} + 'y'}", "@{'/o?a=' + ${f(x, y)}}")) {
      String src = "<a th:href=\"" + link + "\">x</a>";
      assertEquals("<a t:href=\"" + link + "\">x</a>", convert(src), link);
      assertTrue(messages(src).stream().anyMatch(m -> m.contains("non-literal path")), link);
    }
    String literal = "<p th:text=\"|@{'/o?a=' + ${x}}|\">x</p>";
    assertTrue(convert(literal).contains("t:text=\"|@{'/o?a=' + ${x}}|\""), convert(literal));
  }

  @Test
  void linksWithAComputedPathVariableAreReported() throws Exception {
    // petclinic (#430): a fixture owner with a null id made this link a 500.
    String src = "<p t:params=\"Owner owner, Pet pet\"><a th:href=\"@{/owners/{ownerId}(ownerId=${owner.id})}\">a</a>\n"
        + "<a th:href=\"@{/owners/{o}/pets/{p}/edit(o=${owner.id},p=${pet?.id}, q=${pet.name})}\">b</a>\n"
        + "<a th:attr=\"href=@{{id}/edit(id=${owner.id})}\">c</a></p>";
    assertEquals(src.replace("th:", "t:").replace("${owner.id}", "owner.id").replace("${pet?.id}", "pet?.id")
        .replace("${pet.name}", "pet.name"), convert(src));
    assertMessages(src,
        "1: link `@{/owners/{ownerId}(ownerId=owner.id)}` fails the render if a path variable is `null`"
            + " (`ownerId` = `owner.id`), where Thymeleaf wrote an empty segment (`/owners//edit`);"
            + " if it can be `null`, guard the link with `t:if`",
        "2: link `@{/owners/{o}/pets/{p}/edit(o=owner.id,p=pet?.id, q=pet.name)}` fails the render if a path"
            + " variable is `null` (`o` = `owner.id`, `p` = `pet?.id`)",
        "3: link `@{{id}/edit(id=owner.id)}` fails the render if a path variable is `null` (`id` = `owner.id`)");
  }

  @Test
  void linksWithoutAComputedPathVariableAreNotReported() throws Exception {
    assertMessages("<p t:params=\"Owner owner\"><a th:href=\"@{/owners/{id}(id=1)}\">a</a>"
        + "<a th:href=\"@{/owners/{id}/edit(id='new')}\">b</a><a th:href=\"@{/owners(page=${owner.id})}\">c</a>"
        + "<a th:href=\"@{/owners/{id}}\">d</a><a th:href=\"@{/owners/list(id=${owner.id})}\">e</a>"
        + "<b th:text=\"'@{/o/{id}(id=x)}'\">f</b><b th:text=\"|@{/o/{id}(id=${owner.id})}|\">g</b></p>");
    // An empty literal is rejected like null.
    assertMessages("<a t:params=\"Owner owner\" th:href=\"@{/owners/{id}(id='')}\">a</a>",
        "1: link `@{/owners/{id}(id='')}` fails the render if a path variable is `null` (`id` = `''`)");
  }

  @Test
  void selectionsOverSeveralPropertiesAreSplitIntoOnePathEach() throws Exception {
    // petclinic ownerDetails.html (#435): Tessera's *{...} holds one property path.
    String src = "<p t:params=\"Owner owner\"><b th:object=\"${owner}\">"
        + "<i th:text=\"*{firstName + ' ' + lastName}\"></i>"
        + "<i th:text=\"'Owner: ' + *{firstName + ' ' + address?.city}\"></i>"
        + "<i th:if=\"*{pets.size() > 0 and !active}\"></i>"
        + "<i th:with=\"label=*{ age >= 18 ? 'adult' : 'child' }\" th:each=\"p : *{pets ?: others}\"></i>"
        + "<i th:text=\"*{name}\" th:field=\"*{name}\"></i></b></p>";
    assertEquals("<p t:params=\"Owner owner\"><b t:object=\"owner\">"
            + "<i t:text=\"*{firstName} + ' ' + *{lastName}\"></i>"
            + "<i t:text=\"'Owner: ' + (*{firstName} + ' ' + *{address?.city})\"></i>"
            + "<i t:if=\"*{pets.size()} > 0 and !*{active}\"></i>"
            + "<i t:with=\"label=*{age} >= 18 ? 'adult' : 'child'\" t:each=\"p : *{pets} ?: *{others}\"></i>"
            + "<i t:text=\"*{name}\" t:field=\"*{name}\"></i></b></p>",
        convert(src));
    assertMessages(src);
  }

  @Test
  void selectionsThatAreNotOnlyPathsAndOperatorsAreReportedNotSplit() throws Exception {
    String src = "<p t:params=\"Owner owner\"><b th:object=\"${owner}\">"
        + "<i th:text=\"*{pets[0].name + ' ' + lastName}\"></i>\n"
        + "<i th:text=\"*{#strings.toUpperCase(name) + '!'}\"></i>\n"
        + "<i th:if=\"*{name matches 'A.*'}\"></i>\n"
        + "<i th:text=\"|Hi *{firstName + lastName}|\"></i><i th:text=\"'*{a + b}'\"></i><i th:field=\"*{a + b}\"></i>"
        + "</b></p>";
    String out = convert(src);
    assertTrue(out.contains("t:text=\"*{pets[0].name + ' ' + lastName}\""), out);
    assertTrue(out.contains("t:text=\"*{strings.toUpperCase(name) + '!'}\""), out);
    assertTrue(out.contains("t:if=\"*{name matches 'A.*'}\""), out);
    assertTrue(out.contains("t:text=\"|Hi *{firstName + lastName}|\"></i><i t:text=\"'*{a + b}'\"></i><i t:field=\"*{a + b}\""), out);
    assertMessages(src,
        "1: `*{pets[0].name + ' ' + lastName}` is not converted: in Tessera `*{...}` holds one property path",
        "2: `*{strings.toUpperCase(name) + '!'}` is not converted",
        "3: `*{name matches 'A.*'}` is not converted");
  }

  @Test
  void attributesThymeleafWritesEmptyForNullAreReported() throws Exception {
    String src = "<form t:params=\"Owner owner, String u\" th:action=\"${u}\">"
        + "<input th:value=\"${owner.name}\" th:name=\"${owner.field}\">\n"
        + "<a th:href=\"${owner.url}\">a</a><img th:src=\"${owner.photo}\"><button th:type=\"${owner.kind}\">b</button>"
        + "</form>";
    assertMessages(src,
        "1: `th:action` = `u`: for a `null` value Thymeleaf wrote `action=\"\"`, but Tessera omits the attribute;"
            + " if it can be `null` and the empty attribute matters, write `x ?: ''` for a `String`",
        "1: `th:value` = `owner.name`: for a `null` value Thymeleaf wrote `value=\"\"`",
        "1: `th:name` = `owner.field`: for a `null` value Thymeleaf wrote `name=\"\"`",
        "2: `th:href` = `owner.url`: for a `null` value Thymeleaf wrote `href=\"\"`",
        "2: `th:src` = `owner.photo`: for a `null` value Thymeleaf wrote `src=\"\"`",
        "2: `th:type` = `owner.kind`: for a `null` value Thymeleaf wrote `type=\"\"`");
  }

  /** #539: a computed target is a build error unless it is a literal or a choice between literals. */
  @Test
  void nonLiteralTargetIsReportedNotRewritten() throws Exception {
    String src = "<div t:params=\"Link l\"><a th:target=\"${l.target}\">a</a>\n"
        + "<a th:target=\"${l.blank} ? '_blank' : ${l.target}\">a</a><a th:target=\"${l.target} ?: '_self'\">a</a></div>";
    assertMessages(src,
        "1: `th:target` = `l.target`: `target` is not an inert-text attribute in Tessera, so a computed value other"
            + " than a literal is a build error; choose between literals with a boolean model value,"
            + " as in `t:target=\"newWindow ? '_blank' : '_self'\"`",
        "2: `th:target` = `l.blank ? '_blank' : l.target`: `target` is not an inert-text attribute",
        "2: `th:target` = `l.target ?: '_self'`: `target` is not an inert-text attribute");
    assertEquals("<div t:params=\"Link l\"><a t:target=\"l.target\">a</a></div>",
        convert("<div t:params=\"Link l\"><a th:target=\"${l.target}\">a</a></div>"));
    // Literals, a choice between literals, a component prop and a static target are not reported.
    assertMessages("<div t:params=\"Link l\"><a th:target=\"_blank\">a</a><a th:target=\"'_top'\">a</a>"
        + "<a th:target=\"${l.blank} ? '_blank' : '_self'\">a</a>"
        + "<a th:target=\"${l.a} ? '_blank' : (${l.b} ? '_top' : '_self')\">a</a>"
        + "<a th:target=\"${l.a} ? '_blank' : ${l.b} ? '_top' : '_self'\">a</a>"
        + "<my-link th:target=\"${l.target}\"></my-link><a target=\"_blank\">a</a></div>");
  }

  @Test
  void attributesThatCannotBeNullOrThatThymeleafOmitsAreNotReported() throws Exception {
    assertMessages("<form t:params=\"Owner owner\" th:action=\"@{/owners/new}\">"
        + "<input th:value=\"'x'\" th:name=\"|f${owner.id}|\"><input th:value=\"42\" th:type=\"#{input.type}\">"
        + "<input th:title=\"${owner.name}\" th:attr=\"value=${owner.name}\"><input t:value=\"owner.name\">"
        + "<input th:value=\"checkbox\"></form>");
  }

  @Test
  void postFormActionThatIsNotALinkIsReported() throws Exception {
    // #550: the compiler rejects it; the fix is to drop the action or write a link.
    String src = "<div t:params=\"String formAction, Owner owner\">"
        + "<form method=\"post\" th:action=\"${formAction}\"></form>\n"
        + "<form th:action=\"${formAction}\" class=\"f\" METHOD=\"Post\"></form>\n"
        + "<form method=\"post\" t:action=\"formAction\"></form>\n"
        + "<form method=\"post\" th:action=\"|/owners/${owner.id}|\"></form></div>";
    String nullAction = "`th:action` = `formAction`: for a `null` value Thymeleaf wrote `action=\"\"`";
    assertMessages(src,
        "1: " + nullAction,
        "1: `th:action` = `formAction` on a `<form method=\"post\">` is not a link: Tessera adds the CSRF token to a"
            + " POST form only when its action is a same-origin link it can check, so any other computed action is"
            + " a build error; remove `th:action` if the form posts to the page's own URL (a form without an action"
            + " posts to the page URL), or write the action as a link such as `@{/items/{id}(id=${item.id})}`",
        "2: " + nullAction,
        "2: `th:action` = `formAction` on a `<form method=\"post\">` is not a link",
        "3: `t:action` = `formAction` on a `<form method=\"post\">` is not a link",
        "4: `th:action` = `|/owners/${owner.id}|` on a `<form method=\"post\">` is not a link");
    assertTrue(convert(src).contains("<form method=\"post\" t:action=\"formAction\"></form>"));
  }

  @Test
  void postFormActionThatIsALinkOrNotPostIsNotReported() throws Exception {
    assertMessages("<div t:params=\"String formAction, Owner owner\">"
        + "<form method=\"post\" th:action=\"@{/owners/{id}(id=${owner.id})}\"></form>\n"
        + "<form method=\"post\" th:action=\" @{/owners/new} \"></form>"
        + "<form method=\"get\" t:action=\"formAction\"></form>"
        + "<form method=\" post\" t:action=\"formAction\"></form>"
        + "<form method=\"get\" method=\"post\" t:action=\"formAction\"></form>"
        + "<form t:action=\"formAction\"></form>"
        + "<div method=\"post\" t:data-action=\"formAction\"></div></div>",
        "1: link `@{/owners/{id}(id=owner.id)}` fails the render if a path variable is `null`");
  }

  @Test
  void literalTokensAreQuoted() throws Exception {
    assertEquals("<ul t:params=\"Object nav_x\"><li t:class=\"'nav-item'\" t:text=\"'item.name'\" t:value=\"42\""
            + " t:if=\"true\" t:with=\"c=nav_x\" t:attr=\"data-k='a-b'\">x</li></ul>",
        convert("<ul><li th:class=\"nav-item\" th:text=\"item.name\" th:value=\"42\" th:if=\"true\""
            + " t:with=\"c=nav_x\" th:attr=\"data-k=a-b\">x</li></ul>"));
    assertEquals("<b t:class=\"'nav-link'\">x</b>", convert("<b th:class=nav-link>x</b>"));
  }

  @Test
  void tokenLiteralsInLinkParametersAreQuoted() throws Exception {
    // fredbet ranking/list.html (#206): mode=mixed is the text "mixed", not a model attribute.
    assertEquals("<ul t:params=\"Object sortBy\"><li><a t:href=\"@{/ranking(mode='mixed')}\">a</a></li>"
            + "<li><a t:href=\"@{/ranking(mode='adult', sort=sortBy, lang='en', page=2, flag)}\">b</a></li>"
            + "<li><a t:href=\"@{/ranking/{m}(m='child')}\">c</a></li></ul>",
        convert("<ul><li><a th:href=\"@{/ranking(mode=mixed)}\">a</a></li>"
            + "<li><a th:href=\"@{/ranking(mode=adult, sort=${sortBy}, lang=en, page=2, flag)}\">b</a></li>"
            + "<li><a th:href=\"@{/ranking/{m}(m=child)}\">c</a></li></ul>"));
  }

  @Test
  void tokenLiteralsInsideLargerExpressionsAreQuoted() throws Exception {
    assertEquals("<div t:params=\"Object x, Object a, Object b, Object n\">"
            + "<i t:classappend=\"x ? 'active'\"></i><i t:text=\"a ?: 'none'\"></i>"
            + "<i t:if=\"a and b or not x\"></i><i t:if=\"a neq b\"></i><i t:text=\"a == 1-2 ? true : null\"></i>"
            + "<i t:text=\"#{ranking.title('mixed', n)}\"></i>"
            + "<i t:replace=\"~{fragments :: nav(active='ranking')}\"></i>"
            + "<i t:replace=\"fragments :: nav('ranking')\"></i></div>",
        convert("<div><i th:classappend=\"${x} ? active\"></i><i th:text=\"${a} ?: none\"></i>"
            + "<i th:if=\"${a} and ${b} or not ${x}\"></i><i th:if=\"${a} neq ${b}\"></i><i th:text=\"${a} == 1-2 ? true : null\"></i>"
            + "<i th:text=\"#{ranking.title(mixed, ${n})}\"></i>"
            + "<i th:replace=\"~{fragments :: nav(active=ranking)}\"></i>"
            + "<i th:replace=\"fragments :: nav(ranking)\"></i></div>"));
  }

  @Test
  void identifiersInsideVariableAndSelectionExpressionsAreNotTokens() throws Exception {
    // Negative: real variables are still collected as parameters, never quoted.
    assertEquals("<form t:object=\"user\" t:params=\"Object user, Object mode, Object other\">"
            + "<a t:href=\"@{/ranking(mode=mode, x=*{name})}\" t:classappend=\"(mode == other) ? 'on'\""
            + " t:text=\"|${mode} mixed|\" t:with=\"m=mode\">x</a></form>",
        convert("<form th:object=\"${user}\"><a th:href=\"@{/ranking(mode=${mode}, x=*{name})}\""
            + " th:classappend=\"${mode == other} ? on\" th:text=\"|${mode} mixed|\" th:with=\"m=${mode}\">x</a>"
            + "</form>"));
  }

  @Test
  void fragmentReferencesSelectionsAndTesseraValuesAreNotLiteralTokens() throws Exception {
    assertEquals("<div t:params=\"Object nav\"><i t:insert=\"footer :: f\"></i><i t:field=\"name\"></i>"
            + "<i t:class=\"nav\"></i></div>",
        convert("<div><i th:insert=\"footer :: f\"></i><i th:field=\"name\"></i><i t:class=\"nav\"></i></div>"));
  }

  @Test
  void renamedUtilityCallsAndNewIndex() throws Exception {
    assertEquals("<p t:params=\"Object o, Object xs\"><i t:text=\"strings.join(o.pets, ', ')\"></i>"
            + "<i t:if=\"fields.hasErrors('*')\"></i><i t:each=\"e : fields.errors('*')\"></i>"
            + "<i t:text=\"o.isNew() ? 'a' : 'b'\"></i><i t:text=\"xs[0].isNew()\"></i></p>",
        convert("<p><i th:text=\"${#strings.listJoin(o.pets, ', ')}\"></i>"
            + "<i th:if=\"${#fields.hasAnyErrors()}\"></i><i th:each=\"e : ${#fields.allErrors()}\"></i>"
            + "<i th:text=\"${o['new']} ? 'a' : 'b'\"></i><i th:text=\"${xs[0][ 'new' ]}\"></i></p>"));
  }

  @Test
  void globalErrorsConvert() throws Exception {
    assertEquals("<form t:object=\"o\" t:params=\"Object o\"><i t:if=\"fields.hasErrors('global')\"></i>"
            + "<i t:each=\"e : fields.errors('global')\"></i><i t:if=\"fields.hasErrors('global')\"></i>"
            + "<i t:errors=\"*{global}\"></i></form>",
        convert("<form th:object=\"${o}\"><i th:if=\"${#fields.hasGlobalErrors()}\"></i>"
            + "<i th:each=\"e : ${#fields.globalErrors()}\"></i><i th:if=\"${#fields.hasErrors('global')}\"></i>"
            + "<i th:errors=\"*{global}\"></i></form>"));
  }

  @Test
  void doubleBraceSelectionBecomesFieldsValue() throws Exception {
    // #133: *{{path}} in th:object is the field's BindStatus display value, as fields.value is.
    assertEquals("<form t:object=\"o\" t:params=\"Object o\"><b t:text=\"fields.value('due')\"></b>"
            + "<i t:title=\"fields.value('lines[0].due')\"></i><s t:text=\"'*{{due}}'\"></s></form>",
        convert("<form th:object=\"${o}\"><b th:text=\"*{{due}}\"></b><i th:title=\"*{{ lines[0].due }}\"></i>"
            + "<s th:text=\"'*{{due}}'\"></s></form>"));
  }

  @Test
  void idsUtilityConverts() throws Exception {
    // #458: fredbet's labels after t:field checkboxes and radios (bench counts.json: #ids.prev x3).
    assertEquals("<div t:params=\"Object level\">"
            + "<label class=\"form-check-label\" t:for=\"ids.prev('level')\" t:text=\"level\"></label>"
            + "<label t:for=\"ids.next('roles')\"></label><i t:id=\"ids.seq('roles')\"></i></div>",
        convert("<div><label class=\"form-check-label\" th:for=\"${#ids.prev('level')}\" th:text=\"${level}\"></label>"
            + "<label th:for=\"${#ids.next('roles')}\"></label><i th:id=\"${#ids.seq('roles')}\"></i></div>"));
  }

  @Test
  void renamesDoNotApplyInsideStringLiterals() throws Exception {
    String src = "<p th:text=\"'#fields.allErrors() x[' + 'new' + ']' + |#strings.listJoin( ['new']|\">x</p>";
    assertEquals(src.replace("th:", "t:"), convert(src));
  }

  @Test
  void beanReferencesObjectCreationAndAmbientUtilitiesAreReported() throws Exception {
    String src = "<div><p th:text=\"${@env.getProperty('k')}\"></p><p th:text=\"${new java.io.File('/')}\"></p>"
        + "<p th:text=\"${#request.getParameter('q')}\"></p><p th:text=\"${#ctx.session}\"></p>"
        + "<p th:each=\"x : ${xs.![name]}\"></p><a th:href=\"@{${dyn}}\"></a></div>";
    String out = convert(src);
    assertTrue(out.contains("t:text=\"${@env.getProperty('k')}\""), out);
    assertTrue(out.contains("t:text=\"${#request.getParameter('q')}\""), out);
    assertTrue(out.startsWith("<div>"), "no params stub from unconvertible values: " + out);
    assertMessages(src,
        "1: Spring bean references (`@bean`) are not supported",
        "1: object creation with `new` is not supported",
        "1: `#request` reads the servlet request, which a Tessera template cannot see",
        "1: `#ctx.session` reads Thymeleaf's evaluation context",
        "1: collection selection/projection is not supported",
        "1: link with a non-literal path");
  }

  // ---- ambient reads (#436): left in place and reported with where the value comes from ----

  @Test
  void ambientScopeReadsAreReportedWithAFixIt() throws Exception {
    String src = "<div>\n<p th:text=\"${session.user.name}\"></p>\n<p th:if=\"${param.error}\">e</p>\n"
        + "<p th:text=\"${application.version}\"></p>\n<meta name=\"_csrf\" th:content=\"${_csrf.token}\">\n"
        + "<p>[[${param['q']}]] [[${session}]]</p>\n<p th:text=\"${owner.name}\"></p></div>";
    String out = convert(src);
    assertTrue(out.contains("t:text=\"session.user.name\"") && out.contains("t:if=\"param.error\""),
        "reads are not rewritten: " + out);
    assertTrue(out.startsWith("<div t:params=\"Object owner\">"), "no `Object` stub for a scope: " + out);
    assertMessages(src,
        "2: `session.user` reads the HTTP session, which a Tessera template cannot see; in the controller add `user`"
            + " to the model (`@SessionAttribute(name = \"user\", required = false)` on the handler method, or a"
            + " `@ModelAttribute(\"user\")` method in a `@ControllerAdvice` for a value every page needs), declare it in"
            + " `t:params` and read it as `user`",
        "3: `param.error` reads a request parameter, which a Tessera template cannot see; in the controller take it"
            + " as `@RequestParam(name = \"error\", required = false) String error`, add it to the model",
        "4: `application.version` reads an application (servlet context) attribute",
        "5: `_csrf.token` reads Spring Security's CSRF token request attribute, which a Tessera template cannot see;"
            + " the starter already adds the `_csrf` hidden input to every same-origin POST form",
        "6: `param['q']` reads a request parameter, which a Tessera template cannot see; in the controller take it"
            + " as `@RequestParam(name = \"q\"",
        "6: `session` reads the HTTP session, which a Tessera template cannot see; in the controller add `value`",
        "1: `owner` is declared as `Object`");
  }

  @Test
  void ambientExpressionObjectsAreReportedWithAFixIt() throws Exception {
    String src = "<div><p th:text=\"${#httpServletRequest.requestURI}\"></p>"
        + "<p th:text=\"${#session.getAttribute('u')}\"></p><p th:text=\"${#servletContext.contextPath}\"></p>"
        + "<p th:text=\"${#vars['x']}\"></p><p th:text=\"${#execInfo.templateName}\"></p>"
        + "<a th:href=\"${#response.encodeURL('/a')}\"></a><p th:text=\"${#requests.x}\"></p></div>";
    assertEquals(src.replace("th:", "t:"), convert(src));
    assertMessages(src,
        "1: `#httpServletRequest.requestURI` reads the servlet request, which a Tessera template cannot see;"
            + " compute the value in the controller, add it to the model and declare it in `t:params` (a value every"
            + " page needs, such as the request URI, comes from a `@ModelAttribute` method",
        "1: `#session` reads the HTTP session",
        "1: `#servletContext.contextPath` reads the servlet context",
        "1: `#vars` reads Thymeleaf's evaluation context",
        "1: `#execInfo.templateName` reads Thymeleaf's execution info",
        "1: `#response` uses the servlet response",
        "1: Thymeleaf utility object `#requests` has no Tessera equivalent");
  }

  @Test
  void theTemplatesOwnVariablesNamedLikeAScopeAreNotReported() throws Exception {
    // Declared in t:params, by th:with, by th:each, as a fragment parameter: the model's or the
    // template's own variable, not the scope.
    assertMessages("<div t:params=\"User session\"><p th:text=\"${session.name}\"></p></div>");
    assertMessages("<div th:with=\"param=${form}\"><p th:text=\"${param.q}\"></p></div>",
        "1: `form` is declared as `Object`");
    assertMessages("<ul><li th:each=\"application : ${apps}\" th:text=\"${application.name}\"></li></ul>",
        "1: `apps` is declared as `Object`");
    assertMessages("<div th:fragment=\"card(session)\"><p th:text=\"${session.title}\"></p></div>",
        "1: fragment `card` parameters declared as `Object`");
    // A property, a selection, a string or a message key named like a scope is not a read.
    assertMessages("<div t:params=\"Order order\"><p th:text=\"${order.session}\"></p>"
        + "<form th:object=\"${order}\"><p th:text=\"*{param}\"></p></form>"
        + "<p th:text=\"'session.user'\"></p><p th:text=\"#{session.expired}\"></p></div>");
  }

  @Test
  void eventHandlersStylesInlineAndDialectsAreNotRenamed() throws Exception {
    String src = "<div xmlns:sec=\"x\">\n<b th:onclick=\"${a}\" th:style=\"${s}\" th:styleappend=\"${s2}\" th:inline=\"javascript\""
        + " sec:authorize=\"hasRole('A')\" layout:fragment=\"c\" data-th-text=\"${t}\" th:include=\"f\">x</b></div>";
    assertEquals(src, convert(src));
    assertMessages(src,
        "2: `th:onclick` is a build error by design (no dynamic event handlers)",
        "2: `th:style` is not converted; rewrite it as `t:style` from literals, numbers and"
            + " `css.color(...)`/`css.length(...)`/`css.url(...)`",
        "2: `th:styleappend` is not supported; merge it into one `t:style` from literals, numbers and"
            + " `css.color(...)`/`css.length(...)`/`css.url(...)`",
        "2: `th:inline` is not supported",
        "2: `sec:authorize` belongs to the `sec:` dialect",
        "2: `layout:fragment` belongs to the `layout:` dialect",
        "2: `data-th-text` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:text`",
        "2: `th:include` is not supported; use `t:insert` or `t:replace`");
  }

  private static final String SVG_STYLE = "on an SVG element is not converted: Tessera reads";

  /** SVG attributes that take a CSS url() are CSS in Tessera: left in place and reported, like th:style. */
  @Test
  void svgCssAttributesAreNotConverted() throws Exception {
    String src = "<svg th:cursor=\"${c}\">\n<rect th:fill=\"${color}\" th:stroke=\"${s}\" th:width=\"${w}\"/>\n"
        + "<g th:clip-path=\"${a}\" th:mask=\"${b}\" th:filter=\"${f}\"><path th:marker-start=\"${m}\""
        + " th:marker-mid=\"${m}\" th:marker-end=\"${m}\" th:marker=\"${m}\"/></g>\n<RECT TH:FILL=\"${color}\"/></svg>";
    String out = convert(src);
    assertTrue(out.contains("<rect th:fill=\"${color}\" th:stroke=\"${s}\" t:width=\"w\"/>"), out);
    assertTrue(out.contains("<RECT TH:FILL=\"${color}\"/>"), out);
    assertMessages(src,
        "1: `th:cursor` " + SVG_STYLE + " `cursor` as CSS, like `style`, so a `String` value is a build error;"
            + " rewrite it as `t:cursor` from literals, numbers and `css.color(...)`/`css.url(...)`",
        "2: `th:fill` " + SVG_STYLE + " `fill` as CSS",
        "2: `th:stroke` " + SVG_STYLE,
        "3: `th:clip-path` " + SVG_STYLE,
        "3: `th:mask` " + SVG_STYLE,
        "3: `th:filter` " + SVG_STYLE,
        "3: `th:marker-start` " + SVG_STYLE,
        "3: `th:marker-mid` " + SVG_STYLE,
        "3: `th:marker-end` " + SVG_STYLE,
        "3: `th:marker` " + SVG_STYLE,
        "4: `TH:FILL` " + SVG_STYLE + " `FILL` as CSS, like `style`, so a `String` value is a build error;"
            + " rewrite it as `t:FILL`",
        "1: `w` is declared as `Object`");
  }

  /** Outside SVG, inside foreignObject, desc and title, and on an animation's timing fill, the names are plain attributes. */
  @Test
  void otherFillAttributesAreConverted() throws Exception {
    String src = "<div t:params=\"Object c\"><p th:fill=\"${c}\">x</p><svg><rect><animate th:fill=\"${c}\"/>"
        + "<animateMotion th:fill=\"${c}\"/><set th:fill=\"${c}\"/></rect><rect th:fill-opacity=\"${c}\"/>"
        + "<foreignObject><div th:fill=\"${c}\" th:stroke=\"${c}\">x</div></foreignObject>"
        + "<desc><b th:fill=\"${c}\">x</b></desc><title><i th:mask=\"${c}\">x</i></title></svg></div>";
    assertEquals(src.replace("th:", "t:").replace("${c}", "c"), convert(src));
    assertEquals(List.of(), messages(src));
  }

  /** A th:attr entry naming one is converted (its other entries still need it) and reported. */
  @Test
  void svgCssAttributesInThAttrAreReported() throws Exception {
    String src = "<div t:params=\"Object c\"><svg><rect th:attr=\"fill=${c},data-x=${c}, Stroke = ${c}\"/>"
        + "<animate th:attr=\"fill=${c}\"/></svg><p th:attr=\"fill=${c}\">x</p></div>";
    assertEquals(
        "<div t:params=\"Object c\"><svg><rect t:attr=\"fill=c,data-x=c, Stroke = c\"/>"
            + "<animate t:attr=\"fill=c\"/></svg><p t:attr=\"fill=c\">x</p></div>",
        convert(src));
    assertMessages(src,
        "1: `fill` in `th:attr` on an SVG element is CSS in Tessera, like `style`, so a `String` value is a"
            + " build error; write it from literals, numbers and `css.color(...)`/`css.url(...)`",
        "1: `Stroke` in `th:attr` on an SVG element is CSS in Tessera");
  }

  @Test
  void unsupportedConstructsInInlinedTextAreReported() throws Exception {
    String src = "<p>[[${T(System).getenv()}]]</p>";
    assertEquals(src, convert(src));
    assertMessages(src, "1: `T(...)` static class access is not supported");
  }

  // ---- quoted inlines in <script> (#282) ----

  private static final String UNQUOTED = "was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes";
  private static final String IN_COMMENT =
      "inside a JavaScript comment is a build error in Tessera (its value would be written inside the comment";
  private static final String IN_JSON_STRING = "inside a quoted string of a JSON script is a build error in Tessera"
      + " (it writes a JSON value with its own quotes) and was not rewritten: make it the whole value, `{\"name\": [[...]]}`";

  /** A script in a root that already declares its parameters, so no stub items are reported. */
  private static String script(String attributes, String body) {
    return "<div t:params=\"String x, String y\"><script" + attributes + ">" + body + "</script></div>";
  }

  @Test
  void quotedInlineFillingAStringIsUnquoted() throws Exception {
    String src = "<div th:with=\"a=1\"><script>var a = \"[[${user.name}]]\"; var b = '[[${user.id}]]';</script></div>";
    assertEquals(
        "<div t:with=\"a=1\" t:params=\"Object user\"><script>var a = [[${user.name}]]; var b = [[${user.id}]];</script></div>",
        convert(src));
    assertMessages(src,
        "1: `\"[[${user.name}]]\"` in a script " + UNQUOTED + "; a string renders the same, but a non-string"
            + " value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check"
            + " `== \"true\"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a"
            + " non-null value)",
        "1: `'[[${user.id}]]'` in a script " + UNQUOTED,
        "1: `user` is declared as `Object` in the generated `t:params` stub");
  }

  @Test
  void quotedInlineIsUnquotedInEveryJavaScriptAndJsonScript() throws Exception {
    for (String attributes : List.of("", " type=\"module\"", " type=\"TEXT/JavaScript\"", " type",
        " type=\" application/javascript \"", " type=\"application/ld+json\"")) {
      String src = script(attributes, "var a = \"[[${x}]]\";");
      assertEquals(script(attributes, "var a = [[${x}]];"), convert(src), attributes);
    }
    String json = script(" type=\"application/json\"", "{\"a\": \"[[${x}]]\", \"b\": [[${y}]]}");
    assertEquals(script(" type=\"application/json\"", "{\"a\": [[${x}]], \"b\": [[${y}]]}"), convert(json));
    assertMessages(json, "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void quotedInlineInsideThInlineScriptIsUnquotedAndTheAttributeStillReported() throws Exception {
    String src = script(" th:inline=\"javascript\"", "var a = '[[${x}]]';");
    assertEquals(script(" th:inline=\"javascript\"", "var a = [[${x}]];"), convert(src));
    assertMessages(src, "1: `th:inline` is not supported", "1: `'[[${x}]]'` in a script " + UNQUOTED);
  }

  @Test
  void templateLiteralHoldingOnlyAnInlineIsReportedNotUnquoted() throws Exception {
    // Dropping the backticks would turn a tag call String.raw`...` into String.raw"value", so a
    // template literal is never unquoted, tagged or not; the report says what to write.
    for (String body : List.of("var a = `[[${x}]]`;", "`[[${x}]]`", "function f() { return `[[${x}]]`; }",
        "var a = String.raw`[[${x}]]`;", "var a = tag /* c */ `[[${x}]]`;", "var a = f()`[[${x}]]`;")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src, "1: `` `[[${x}]]` `` in a script is a template literal, and `[[...]]` inside it is a"
          + " build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a"
          + " tagged template declare the value above the code (`var v = [[...]];`) and write ``tag`${v}` `` after it");
    }
    // A quoted inline inside template code is still unquoted, and then reported.
    String nested = script("", "var a = `${\"[[${x}]]\"}`;");
    assertEquals(script("", "var a = `${[[${x}]]}`;"), convert(nested));
    assertMessages(nested, "1: `\"[[${x}]]\"` in a script " + UNQUOTED, "1: `[[${x}]]` " + IN_TEMPLATE);
  }

  // ---- the script prelude (#540): values outside it are hoisted (#541) ----

  private static final String HOISTED = "in a script was moved into";
  private static final String IN_TEMPLATE = "inside a template literal is a build error in Tessera and was not rewritten";
  private static final String IN_REGEX =
      "inside a JavaScript regular expression is a build error in Tessera and was not rewritten";

  @Test
  void valueOutsideThePreludeIsHoistedIntoAVarDeclaration() throws Exception {
    // Each case is a shape from the bench apps; the hoisted declarations go above the first code.
    String[][] cases = {
        {"var d = [[${x}]].split(\".\");", "var _t1 = [[${x}]]; var d = _t1.split(\".\");"},
        {"init([[${x}]], 2);", "var _t1 = [[${x}]]; init(_t1, 2);"},
        {"var o = {a: [[${x}]], b: [[${y}]]};", "var _t1 = [[${x}]]; var _t2 = [[${y}]]; var o = {a: _t1, b: _t2};"},
        {"var a = [[${x}]]; f(a); var b = [[${y}]];", "var a = [[${x}]]; var _t1 = [[${y}]]; f(a); var b = _t1;"},
        {"const a = [[${x}]]", "var _t1 = [[${x}]]; const a = _t1"},
        {"let a = [[${x}]] + 1;", "var _t1 = [[${x}]]; let a = _t1 + 1;"},
        {"return[[${x}]]", "var _t1 = [[${x}]]; return _t1"},
        {"var location = [[${x}]];", "var _t1 = [[${x}]]; var location = _t1;"},
    };
    for (String[] c : cases) {
      String src = script("", c[0]);
      assertEquals(script("", c[1]), convert(src), c[0]);
    }
    String message = script("", "f([[${x}]]);");
    assertMessages(message, "1: `[[${x}]]` in a script was moved into `var _t1 = [[${x}]];` at the top of the script"
        + " and replaced by `_t1`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration"
        + " at the top, before any other code; `_t1` is a global variable in a classic script, so rename it if another"
        + " script on the page uses that name");
    assertEquals(1, messages(message).size());
  }

  @Test
  void keywordFreeAssignmentIsHoisted() throws Exception {
    // budgetmaster: the declarations go on their own lines, at the indentation of the first code.
    String src = "<div t:params=\"String x, String y\">\n    <script>\n"
        + "        numberValidationMessage = [[${x}]];\n"
        + "        numberValidationMessageZeroAllowed = [[${y}]];\n    </script></div>";
    assertEquals("<div t:params=\"String x, String y\">\n    <script>\n"
        + "        var _t1 = [[${x}]];\n        var _t2 = [[${y}]];\n"
        + "        numberValidationMessage = _t1;\n"
        + "        numberValidationMessageZeroAllowed = _t2;\n    </script></div>", convert(src));
    assertMessages(src, "3: `[[${x}]]` " + HOISTED + " `var _t1", "4: `[[${y}]]` " + HOISTED + " `var _t2");
    // After a prelude, below it; a CRLF script keeps CRLF; // comments stay above.
    String crlf = script("", "\r\n  // data\r\n  var a = [[${x}]];\r\n  f(a, [[${y}]]);\r\n");
    assertEquals(script("", "\r\n  // data\r\n  var a = [[${x}]];\r\n  var _t1 = [[${y}]];\r\n  f(a, _t1);\r\n"),
        convert(crlf));
  }

  @Test
  void hoistedNamesAreNewInTheFile() throws Exception {
    // Numbered across the scripts of a file, skipping the names the file already uses.
    String src = "<div t:params=\"String x, String y\"><script>var _t1 = 1; f([[${x}]]);</script>"
        + "<script>g([[${y}]]);</script></div>";
    assertEquals("<div t:params=\"String x, String y\"><script>var _t2 = [[${x}]]; var _t1 = 1; f(_t2);</script>"
        + "<script>var _t3 = [[${y}]]; g(_t3);</script></div>", convert(src));
  }

  private static final String DEFERRED = "; it was inside a block or function: code that runs later, such as a"
      + " callback or event handler, now reads the global `_t1` when it runs, and if the script is included more than"
      + " once (a fragment per row) each copy overwrites it, so every callback reads the last copy's value; give each"
      + " copy its own value, for example from a `data-*` attribute of the element it handles";

  @Test
  void valueInsideABlockOrFunctionIsHoistedAndReported() throws Exception {
    // A row fragment: every copy assigns the same global before the callbacks run.
    for (String body : List.of("$(function () { $('#del').click(function () { remove([[${x}]]); }); });",
        "if (a) { f([[${x}]]); }", "el.addEventListener('click', () => { f({id: [[${x}]]}); });",
        "$('#del').click(() => remove([[${x}]]));", "setTimeout(() => f(1, [[${x}]]), 0);", "var g = x => [[${x}]];",
        "f(() => [1, [[${x}]]]);")) {
      String src = script("", body);
      assertEquals(script("", "var _t1 = [[${x}]]; " + body.replace("[[${x}]]", "_t1")), convert(src), body);
      List<String> messages = messages(src);
      assertEquals(1, messages.size(), messages.toString());
      assertTrue(messages.get(0).endsWith(DEFERRED), messages.toString());
    }
    // Not in an object literal or a call at the top level.
    for (String body : List.of("var o = {a: [[${x}]]};", "f({a: [[${x}]]});", "init([[${x}]]);",
        "f(() => 1, [[${x}]]);", "var g = () => 1; h([[${x}]]);", "a.forEach(function (v) { g(v); }); h([[${x}]]);")) {
      List<String> messages = messages(script("", body));
      assertTrue(!messages.get(0).contains("inside a block or function"), messages.toString());
    }
  }

  @Test
  void preludeNamesMayStartWithADollarSign() throws Exception {
    // The compiler's grammar: an ASCII identifier, $ and _ included, not starting with a digit.
    String src = script("", "var $form = [[${x}]]; const _a1 = [[${y}]];");
    assertEquals(src, convert(src));
    String digit = script("", "var 1a = [[${x}]];");
    assertEquals(script("", "var _t1 = [[${x}]]; var 1a = _t1;"), convert(digit));
  }

  @Test
  void valuesInTemplateLiteralsAndRegularExpressionsAreReportedNotHoisted() throws Exception {
    String[][] cases = {
        {"var a = `${[[${x}]]}`;", IN_TEMPLATE},
        {"var a = `${f(\"a [[${x}]]\")}`;", IN_TEMPLATE},
        {"var r = /a[[${x}]]/g;", IN_REGEX},
        {"if (/[/][[${x}]]/.test(s)) {}", IN_REGEX},
        {"return /[[${x}]]/;", IN_REGEX},
        {"if (ok) /[[${x}]]/.test(s);", IN_REGEX},
        {"while (f()) {} /[[${x}]]/.test(s);", IN_REGEX},
    };
    for (String[] c : cases) {
      String src = script("", c[0]);
      assertEquals(src, convert(src), c[0]);
      assertMessages(src, "1: `[[${x}]]` " + c[1]);
    }
    // A division is not a regular expression: the value after it is hoisted, and so are the
    // others of a script with a reported one.
    String division = script("", "var h = (w) / 2; var o = {} / 1; var a = [[${x}]] / 2; var r = /[[${y}]]/;");
    assertEquals(script("", "var _t1 = [[${x}]]; var h = (w) / 2; var o = {} / 1; var a = _t1 / 2; var r = /[[${y}]]/;"),
        convert(division));
    assertMessages(division, "1: `[[${x}]]` " + HOISTED, "1: `[[${y}]]` " + IN_REGEX);
  }

  @Test
  void useStrictThatNoLongerStartsTheScriptIsReported() throws Exception {
    String src = script("", "'use strict';\nf([[${x}]]);");
    assertEquals(script("", "var _t1 = [[${x}]]; 'use strict';\nf(_t1);"), convert(src));
    assertMessages(src, "2: `[[${x}]]` " + HOISTED, "1: `'use strict'` no longer starts the script");
  }

  @Test
  void naturalTemplateDefaultsAndUnquotedStringsAreRewrittenBeforeHoisting() throws Exception {
    String unquoted = script("", "var a = \"[[${x}]]\" / 2;\nvar b = [[${y}]];");
    assertEquals(script("", "var _t1 = [[${x}]]; var _t2 = [[${y}]]; var a = _t1 / 2;\nvar b = _t2;"),
        convert(unquoted));
    assertMessages(unquoted, "1: `\"[[${x}]]\"` in a script " + UNQUOTED, "1: `[[${x}]]` " + HOISTED,
        "2: `[[${y}]]` " + HOISTED);
    String natural = script(JS, "var a = /*[[${x}]]*/ {\"k\": [1,\n 2]};\nvar r = /a/;\nvar b = [[${y}]];");
    assertEquals(script(JS, "var a = [[${x}]];\nvar _t1 = [[${y}]];\nvar r = /a/;\nvar b = _t1;"), convert(natural));
    assertMessages(natural, TH_INLINE, "1: `/*[[${x}]]*/ {", "4: `[[${y}]]` " + HOISTED);
    // The text Thymeleaf drops after a natural-template comment it reports stays as it is.
    String manual = script(JS, "var a = /*[[${x}]]*/ [[[${y}]], 1 + 2];");
    assertEquals(manual, convert(manual));
    // Not a JavaScript script, or th:inline="none": not rewritten.
    for (String src : List.of(script(" type=\"text/x-template\"", "var r = /a/; [[${x}]]"),
        script(" type=\"application/json\"", "{\"a\": [1, [[${x}]]]}"),
        "<script th:inline=\"none\">var r = /a/; [[${x}]]</script>")) {
      assertEquals(src, convert(src), src);
      assertEquals(src.contains("none") ? List.of("1: `th:inline` is not supported; use `[[...]]` in `<script>`"
          + " (JSON-escaped) or move data to `data-*` attributes") : List.of(), messages(src), src);
    }
  }

  @Test
  void stringsAroundAnUnquotedInlineAreKept() throws Exception {
    // Exactly one inline between two quotes is a whole string literal, even between concatenations.
    String src = script("", "var a = 'a' + \"[[${x}]]\" + 'b';");
    assertEquals(script("", "var _t1 = [[${x}]]; var a = 'a' + _t1 + 'b';"), convert(src));
    assertMessages(src, "1: `\"[[${x}]]\"` in a script " + UNQUOTED, "1: `[[${x}]]` " + HOISTED);
    // Quotes inside the expression don't end or start a string.
    String quotes = script("", "var a = [[${x == '\"'}]]; var b = \"[[${y + 'a'}]]\";");
    assertEquals(script("", "var a = [[${x == '\"'}]]; var b = [[${y + 'a'}]];"), convert(quotes));
    assertMessages(quotes, "1: `\"[[${y + 'a'}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void inlineSharingAStringWithOtherTextIsHoistedAndTheStringSplit() throws Exception {
    String[][] cases = {
        {"var a = \"Hello [[${x}]]!\";", "var _t1 = [[${x}]]; var a = \"Hello \" + _t1 + \"!\";"},
        {"var a = 'Hello [[${x}]]';", "var _t1 = [[${x}]]; var a = 'Hello ' + _t1;"},
        {"var a = \"[[${x}]]!\";", "var _t1 = [[${x}]]; var a = _t1 + \"!\";"},
        {"var a = \"\\\"[[${x}]]\";", "var _t1 = [[${x}]]; var a = \"\\\"\" + _t1;"},
        {"var a = \"[[${x}]] [[${y}]]\";", "var _t1 = [[${x}]]; var _t2 = [[${y}]]; var a = _t1 + \" \" + _t2;"},
        {"var a = \"[[${x}]][[${y}]]\";", "var _t1 = [[${x}]]; var _t2 = [[${y}]]; var a = \"\" + _t1 + _t2;"},
        {"f('a [[${x}]]', 1);", "var _t1 = [[${x}]]; f('a ' + _t1, 1);"},
        {"return 'a [[${x}]]'", "var _t1 = [[${x}]]; return 'a ' + _t1"},
        // In parentheses where the tokens around the string bind tighter than +.
        {"var n = \"a [[${x}]]\".length;", "var _t1 = [[${x}]]; var n = (\"a \" + _t1).length;"},
        {"var n = 1 - 'a [[${x}]]';", "var _t1 = [[${x}]]; var n = 1 - ('a ' + _t1);"},
        {"var n = !'a [[${x}]]';", "var _t1 = [[${x}]]; var n = !('a ' + _t1);"},
        // After a +, a string that now starts with the value: n + _t1 would add two numbers.
        {"var a = n + \"[[${x}]]!\";", "var _t1 = [[${x}]]; var a = n + (_t1 + \"!\");"},
        {"var a = n + \"a [[${x}]]\";", "var _t1 = [[${x}]]; var a = n + \"a \" + _t1;"},
        {"var n = +\"0.[[${x}]]\";", "var _t1 = [[${x}]]; var n = +(\"0.\" + _t1);"},
    };
    for (String[] c : cases) {
      String src = script("", c[0]);
      assertEquals(script("", c[1]), convert(src), c[0]);
    }
    String src = script("", "var a = 'Hello [[${x}]]';");
    assertMessages(src, "1: `[[${x}]]` " + HOISTED + " `var _t1 = [[${x}]];`");
    assertTrue(messages(src).get(0).endsWith("; it was inside a quoted string, which is now joined with `+`: a"
        + " non-string value renders as JSON (`null`, not an empty string)"), messages(src).toString());
    // A template literal is reported, not split.
    String template = script("", "var a = `a ${1} [[${x}]]`;");
    assertEquals(template, convert(template));
    // Its fix-it is the declaration the compiler accepts, not a `+` concatenation, which is still a build error.
    assertEquals(List.of("1: `[[${x}]]` " + IN_TEMPLATE
        + ": declare the value at the top of the script (`var v = [[...]];`) and write `${v}`"), messages(template));
    // In a JSON script a string is reported.
    String json = script(" type=\"application/json\"", "{\"a\": \"Hello [[${x}]]\"}");
    assertEquals(json, convert(json));
    assertMessages(json, "1: `[[${x}]]` " + IN_JSON_STRING + ", and build any text around it in the model");
  }

  @Test
  void quotedInlineIsReportedOnItsOwnLine() throws Exception {
    String src = "<div t:params=\"String x\">\n<script>\n  var a = 1;\n  var b = \"x [[${x}]]\";\n"
        + "  var c = \"[[${x}]]\";\n</script></div>";
    assertEquals("<div t:params=\"String x\">\n<script>\n  var _t1 = [[${x}]];\n  var _t2 = [[${x}]];\n"
        + "  var a = 1;\n  var b = \"x \" + _t1;\n  var c = _t2;\n</script></div>", convert(src));
    assertMessages(src, "5: `\"[[${x}]]\"` in a script " + UNQUOTED, "4: `[[${x}]]` " + HOISTED,
        "5: `[[${x}]]` " + HOISTED);
  }

  @Test
  void inlineInsideAJavaScriptCommentIsReportedNotRewritten() throws Exception {
    // The compiler rejects [[...]] in a comment; the natural-template rewrite is #315.
    for (String body : List.of(
        "var a = /*[[${x}]]*/ 'd';",
        "// \"[[${x}]]\"\nvar a = 1;",
        "/* \"[[${x}]]\" */ var a = 1;",
        "/*/ \"[[${x}]]\" */",
        "var a = 1; // [[${x}]]")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src, "1: `[[${x}]]` " + IN_COMMENT);
    }
    String two = script("", "var a = 1; /* [[${x}]]\n [[${y}]] */");
    assertMessages(two, "1: `[[${x}]]` " + IN_COMMENT, "2: `[[${y}]]` " + IN_COMMENT);
  }

  // ---- natural-template comments in th:inline="javascript" scripts (#315) ----

  private static final String TH_INLINE = "1: `th:inline` is not supported";
  private static final String JS = " th:inline=\"javascript\"";

  @Test
  void naturalTemplateCommentIsRewrittenToItsInline() throws Exception {
    // Each expected output is what thymeleaf 3.1.3 renders for the input, with the inline in place
    // of its value (checked by hand against Thymeleaf; it is not a dependency of tessera-migrate).
    // The fifth column is the output after a value outside the prelude is hoisted (#541).
    String[][] cases = {
        {"var a = /*[[${x}]]*/ \"d\";", "var a = [[${x}]];", "`/*[[${x}]]*/ \"d\"`", "the default `\"d\"`"},
        {"f(/*[[${x}]]*/ null);", "f([[${x}]]);", "`/*[[${x}]]*/ null`", "the default `null`",
            "var _t1 = [[${x}]]; f(_t1);"},
        {"var a = /*[[${x}]]*/ -1.5e3;", "var a = [[${x}]];", "`/*[[${x}]]*/ -1.5e3`", "the default `-1.5e3`"},
        {"var a = /*[[${x}]]*/ 'x/y';", "var a = [[${x}]];", "`/*[[${x}]]*/ 'x/y'`", "the default `'x/y'`"},
        {"var a = /*[[${x}]]*/ [ 'a;b' ];", "var a = [[${x}]];", "`/*[[${x}]]*/ [ 'a;b' ]`", "the default"},
        {"var a = /*[[${x}]]*/ {\"k\": [1,\n 2]};\nvar c;", "var a = [[${x}]];\nvar c;",
            "`/*[[${x}]]*/ {\"k\": [1,\n 2]}`", "the default"},
        {"var a = /*[[${x}]]*/;", "var a = [[${x}]];", "`/*[[${x}]]*/`", "no default"},
        {"var a = /*[[${x}]]*/\nvar b;", "var a = [[${x}]]\nvar b;", "`/*[[${x}]]*/`", "no default",
            "var _t1 = [[${x}]]; var a = _t1\nvar b;"},
        {"var a = /*[[${x}]]*/ 'd'", "var a = [[${x}]]", "`/*[[${x}]]*/ 'd'`", "the default `'d'`",
            "var _t1 = [[${x}]]; var a = _t1"},
        {"var a = /*[[${x}]]*/ 'd' /* c */;", "var a = [[${x}]]/* c */;", "`/*[[${x}]]*/ 'd'`", "the default",
            "var _t1 = [[${x}]]; var a = _t1/* c */;"},
        {"var a = /*[[${x}]]*/ 'd' // c\nvar b;", "var a = [[${x}]]// c\nvar b;", "`/*[[${x}]]*/ 'd'`", "the default",
            "var _t1 = [[${x}]]; var a = _t1// c\nvar b;"},
    };
    for (String[] c : cases) {
      String src = script(JS, c[0]);
      assertEquals(script(JS, c.length > 4 ? c[4] : c[1]), convert(src), c[0]);
      String rewritten = "1: " + c[2] + " in a script was rewritten to `[[${x}]]`: Thymeleaf's natural"
          + " template wrote the value in place of the comment and dropped " + c[3];
      if (c.length > 4) {
        assertMessages(src, TH_INLINE, rewritten, "1: `[[${x}]]` " + HOISTED);
      } else {
        assertMessages(src, TH_INLINE, rewritten);
      }
    }
    String two = script(JS, "var a = /*[[${x}]]*/ 0, b = /*[[${y}]]*/ true;");
    assertEquals(script(JS, "var _t1 = [[${x}]]; var _t2 = [[${y}]]; var a = _t1, b = _t2;"), convert(two));
    assertMessages(two, TH_INLINE, "1: `/*[[${x}]]*/ 0` in a script was rewritten",
        "1: `/*[[${y}]]*/ true` in a script was rewritten", "1: `[[${x}]]` " + HOISTED, "1: `[[${y}]]` " + HOISTED);
    // A JSON script, and th:inline="javascript" on an ancestor.
    String json = script(JS + " type=\"application/json\"", "{\"a\": /*[[${x}]]*/ \"d\"}");
    assertEquals(script(JS + " type=\"application/json\"", "{\"a\": [[${x}]]}"), convert(json));
    String ancestor = "<div th:inline=\"javascript\" t:params=\"String x\"><script>var a = /*[[${x}]]*/ 'd';</script></div>";
    assertEquals(ancestor.replace("/*[[${x}]]*/ 'd'", "[[${x}]]"), convert(ancestor));
    assertMessages(ancestor, TH_INLINE, "1: `/*[[${x}]]*/ 'd'` in a script was rewritten");
    // [(...)] the same way; Tessera rejects it in a script, which the report says.
    String raw = script(JS, "var a = /*[(${y})]*/ 0;");
    assertEquals(script(JS, "var a = [(${y})];"), convert(raw));
    assertMessages(raw, TH_INLINE, "1: `/*[(${y})]*/ 0` in a script was rewritten to `[(${y})]`", "1: `[(...)]` prints");
    assertTrue(messages(raw).get(1).endsWith("; `[(...)]` is a build error in a Tessera script: write `[[...]]`,"
        + " which outputs a JSON value, or pass code another way"), messages(raw).toString());
  }

  @Test
  void naturalTemplateCommentWithoutOneLiteralIsReportedNotRewritten() throws Exception {
    String manual = " in a script is a Thymeleaf natural-template comment, but it is not one inline followed by one"
        + " literal (Thymeleaf drops ";
    String[][] cases = {
        {"var a = /*[[${x}]]*/ 'd' + 'e';", "`/*[[${x}]]*/`", "`'d' + 'e'`"},
        {"var a = /*[[${x}]]*/ foo.bar(1);", "`/*[[${x}]]*/`", "`foo.bar(1`"},
        {"var a = /*[[${x}]]*/ 'a\\'b';", "`/*[[${x}]]*/`", "`'a\\'b'`"},
        {"var a = /*[[${x}]]*/ `d`;", "`/*[[${x}]]*/`", "`` `d` ``"},
        {"var a = /*[[${x}]]*/ 1 / 2;", "`/*[[${x}]]*/`", "`1 / 2`"},
        {"var a = /*[[${x}]]*/ ['[[${y}]]'];", "`/*[[${x}]]*/`", "`['[[${y}]]']`"},
        {"var a = /*[[${x}]] + [[${y}]]*/ 'd';", "`/*[[${x}]] + [[${y}]]*/`", "`'d'`"},
    };
    for (String[] c : cases) {
      String src = script(JS, c[0]);
      assertEquals(src, convert(src), c[0]);
      List<String> messages = messages(src);
      assertTrue(messages.get(0).startsWith(TH_INLINE), c[0]);
      assertEquals("1: " + c[1] + manual + c[2] + " after it, up to", messages.get(1).substring(0,
          ("1: " + c[1] + manual + c[2] + " after it, up to").length()), c[0]);
    }
  }

  @Test
  void commentsThatAreNotNaturalTemplatesAreNotRewritten() throws Exception {
    // Without th:inline="javascript" Thymeleaf writes the value inside the comment and keeps the default.
    for (String attributes : List.of("", " th:inline=\"text\"")) {
      String src = script(attributes, "var a = /*[[${x}]]*/ 'd';");
      assertEquals(src, convert(src), attributes);
      assertTrue(messages(src).contains("1: `[[${x}]]` " + IN_COMMENT
          + " and the page would never use it); move it out of the comment: Thymeleaf's natural template"
          + " `var a = /*[[${x}]]*/ 'd';` becomes `var a = [[${x}]];`"), messages(src).toString());
    }
    // Not the whole comment, in a line comment, or in a string: Thymeleaf leaves them alone too.
    for (String body : List.of("var a = /* [[${x}]] */ 'd';", "var a = /*[[${x}]] */ 'd';",
        "// /*[[${x}]]*/ 'd'\nvar a = 1;")) {
      String src = script(JS, body);
      assertEquals(src, convert(src), body);
      assertMessages(src, TH_INLINE, "1: `[[${x}]]` " + IN_COMMENT);
    }
    String string = script(JS, "var a = '/*[[${x}]]*/ d';");
    assertEquals(script(JS, "var _t1 = [[${x}]]; var a = '/*' + _t1 + '*/ d';"), convert(string));
    assertMessages(string, TH_INLINE, "1: `[[${x}]]` " + HOISTED);
    // th:inline="none" and a non-JavaScript script are untouched.
    for (String src : List.of(
        "<script th:inline=\"none\">var a = /*[[${x}]]*/ 'd';</script>",
        "<div th:inline=\"javascript\" t:params=\"String x\"><script type=\"text/x-template\">var a = /*[[${x}]]*/ 'd';</script></div>")) {
      assertEquals(src, convert(src), src);
      assertMessages(src, TH_INLINE);
    }
  }

  @Test
  void inlinesOutsideJavaScriptStringsAreUnchanged() throws Exception {
    for (String body : List.of(
        "var a = [[${x}]];",
        "var a = [[${x}]]; var r = /a/g; var t = `${b}`; var c = 1 / 2; #!",
        "// a\n  const a = [[${x}]];\n  let b = [[${y}]]; f(a, b);")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertEquals(List.of(), messages(src), body);
    }
    // Outside the prelude they are hoisted, with no other change.
    String[][] hoisted = {
        {"var a = \"a\" + [[${x}]] + \"b\";", "var _t1 = [[${x}]]; var a = \"a\" + _t1 + \"b\";"},
        {"var re = \"a/b`\"; var a = [[${x}]].length;", "var _t1 = [[${x}]]; var re = \"a/b`\"; var a = _t1.length;"},
        {"var a = \"done\"; var b = [[${x}]]; var c = 'it\\'s';",
            "var _t1 = [[${x}]]; var a = \"done\"; var b = _t1; var c = 'it\\'s';"},
        {"var a = \"unterminated\nvar b = [[${x}]];", "var _t1 = [[${x}]]; var a = \"unterminated\nvar b = _t1;"},
    };
    for (String[] c : hoisted) {
      String src = script("", c[0]);
      assertEquals(script("", c[1]), convert(src), c[0]);
      assertMessages(src, (c[0].contains("\n") ? "2" : "1") + ": `[[${x}]]` " + HOISTED);
    }
    for (String src : List.of(
        "<div t:params=\"String x\"><p>\"[[${x}]]\"</p><b title=\"'[[${x}]]'\" onclick=\"f('[[${x}]]')\">b</b></div>",
        script(" type=\"text/x-template\"", "\"[[${x}]]\""),
        script(" t:type=\"y\"", "\"[[${x}]]\""),
        script(" type=\"text/x-template\"", "a") + "<p>'[[${x}]]'</p>")) {
      assertEquals(src, convert(src), src);
      assertEquals(List.of(), messages(src), src);
    }
    // A type set by an expression is not a JavaScript script (the compiler rejects inlines there).
    String dynamic = script(" th:type=\"${y}\"", "\"[[${x}]]\"");
    assertEquals(script(" t:type=\"y\"", "\"[[${x}]]\""), convert(dynamic));
    assertMessages(dynamic, "1: `th:type` = `y`: for a `null` value Thymeleaf wrote `type=\"\"`"); // #435
  }

  @Test
  void thInlineNoneLeavesScriptsAlone() throws Exception {
    // th:inline="none" is Thymeleaf's way to leave [[ alone: no rewrite, no roots, only the th:inline item.
    for (String attributes : List.of(" th:inline=\"none\"", " TH:INLINE=\" None \"", " th:inline='none'")) {
      String src = "<script" + attributes + ">var d = [\"[[\", \"]]\"]; var s = \"[[${x}]]\";</script>";
      assertEquals(src, convert(src), attributes);
      assertMessages(src, "1: `th:inline` is not supported");
    }
    // Only "none" turns inlining off; th:inline="text" still processes [[...]].
    String text = script(" th:inline=\"text\"", "var a = \"[[${x}]]\";");
    assertEquals(script(" th:inline=\"text\"", "var a = [[${x}]];"), convert(text));
    assertMessages(text, "1: `th:inline` is not supported", "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void thInlineNoneOnAnAncestorAppliesUntilOverridden() throws Exception {
    String ancestor = "<div th:inline=\"none\" t:params=\"String x\"><p>[[${y}]]</p>"
        + "<script>var s = \"[[${x}]]\";</script></div>";
    assertEquals(ancestor, convert(ancestor));
    assertMessages(ancestor, "1: `th:inline` is not supported");
    // The nearest th:inline wins.
    String nested = "<div th:inline=\"none\" t:params=\"String x\"><script th:inline=\"javascript\">"
        + "var a = \"[[${x}]]\";</script><script>var b = \"[[${x}]]\";</script></div>";
    assertEquals(nested.replace("var a = \"[[${x}]]\"", "var a = [[${x}]]"), convert(nested));
    assertMessages(nested, "1: `th:inline` is not supported", "1: `th:inline` is not supported",
        "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
    // A close tag ends unclosed children; a stray close tag ends nothing.
    String closes = "<body t:params=\"String x\"><div th:inline=\"none\"><p>a<p>b</div></b>"
        + "<script>var s = \"[[${x}]]\";</script></body>";
    assertEquals(closes.replace("\"[[${x}]]\"", "[[${x}]]"), convert(closes));
    assertMessages(closes, "1: `th:inline` is not supported", "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void lineTerminatorsInsideStringsFollowEs2019() throws Exception {
    // U+2028 and U+2029 don't end a string, and "\" + CRLF is a line continuation.
    for (String body : List.of("var a = \"x  [[${x}]]\";", "var a = \"x  [[${x}]]\";",
        "var a = \"line \\\r\n[[${x}]]\";", "var a = \"line \\\n[[${x}]]\";", "var a = \"line \\\r[[${x}]]\";")) {
      String src = script("", body);
      assertEquals(script("", "var _t1 = [[${x}]]; " + body.replace("[[${x}]]\"", "\" + _t1")), convert(src), body);
      assertMessages(src, (body.contains("\n") ? "2" : "1") + ": `[[${x}]]` " + HOISTED);
    }
    // A bare line break still ends an unterminated string.
    String bare = script("", "var a = \"x\n[[${x}]]\";");
    assertEquals(script("", "var _t1 = [[${x}]]; var a = \"x\n_t1\";"), convert(bare));
    assertMessages(bare, "2: `[[${x}]]` " + HOISTED);
  }

  @Test
  void quotesInsideTheInlineExpressionDoNotEndIt() throws Exception {
    // A '...' string in the expression may hold "]]"; the end is found the way the compiler finds it.
    String whole = "<div t:params=\"Object m\"><script>var k = \"[[${m['k]]']}]]\";</script></div>";
    assertEquals("<div t:params=\"Object m\"><script>var k = [[${m['k]]']}]];</script></div>", convert(whole));
    assertMessages(whole, "1: `\"[[${m['k]]']}]]\"` in a script " + UNQUOTED);
    String part = "<div t:params=\"Object m\"><script>var r = \"a [[${m['k]]']}]]\";</script></div>";
    assertEquals("<div t:params=\"Object m\"><script>var _t1 = [[${m['k]]']}]]; var r = \"a \" + _t1;</script></div>",
        convert(part));
    assertMessages(part, "1: `[[${m['k]]']}]]` " + HOISTED);
    String roots = "<div><script>var k = \"[[${m['k]]']}]]\";</script></div>";
    assertEquals("<div t:params=\"Object m\"><script>var k = [[${m['k]]']}]];</script></div>", convert(roots));
  }

  @Test
  void literalTextThatLooksDangerousIsNotReported() throws Exception {
    String src = "<p th:text=\"'T(x) new Foo @bean #ctx.x'\">x</p>";
    assertEquals("<p t:text=\"'T(x) new Foo @bean #ctx.x'\">x</p>", convert(src));
    assertEquals(List.of(), messages(src));
  }
}
