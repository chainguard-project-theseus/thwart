package dev.chainguard.thwart.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
    // Thymeleaf renders the content of <!--/*/ ... /*/-->; Thwart rejects the markers (#132).
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

  /**
   * #587: migrate has no copy of the compiler's attribute-name rules. A computed name outside HTML
   * (data-*, htmx, Vue, Alpine, AngularJS spellings, a custom element's prop, any other name) is
   * converted like any other attribute, with no rename and no report: the compiler's neutral-value
   * error and fix-it (guide section 4.4, Framework-neutral values) are the single source.
   */
  @Test
  void namesOutsideHtmlAreConvertedWithoutRenameOrReport() throws Exception {
    String src = "<div t:params=\"String x\"><a th:hx-target=\"${x}\" th:HX-Swap=\"'innerHTML'\" t:hx-confirm=\"x\""
        + " th:up-on-loaded=\"${x}\" th:data:foo=\"${x}\" th:data-ng-init=\"${x}\" th:x_ng-click=\"${x}\""
        + " th:v-bind:title=\"${x}\" th:hx-get=\"@{/a}\" th:hx-vals=\"${x}\" th:x-text=\"${x}\">a</a>"
        + "<my-list th:items=\"${x}\"></my-list></div>";
    assertEquals("<div t:params=\"String x\"><a t:hx-target=\"x\" t:HX-Swap=\"'innerHTML'\" t:hx-confirm=\"x\""
        + " t:up-on-loaded=\"x\" t:data:foo=\"x\" t:data-ng-init=\"x\" t:x_ng-click=\"x\""
        + " t:v-bind:title=\"x\" t:hx-get=\"@{/a}\" t:hx-vals=\"x\" t:x-text=\"x\">a</a>"
        + "<my-list t:items=\"x\"></my-list></div>", convert(src));
    assertMessages(src);
  }

  /** #766: th:attr and th:errorclass are left in place and reported with the recipe, not rewritten. */
  @Test
  void attrAndErrorClassAreReportedNotRewritten() throws Exception {
    String src = "<div t:params=\"String x\"><a th:attr=\"title=${x}\" th:errorclass=\"err\">a</a></div>";
    assertEquals(src, convert(src));
    assertMessages(src,
        "1: `th:attr` is not supported; write one `t:<name>` per attribute: `th:attr=\"title=${a}, href=${b}\"`"
            + " becomes `t:title=\"a\" t:href=\"b\"`",
        "1: `th:errorclass` is not supported; write `t:classappend=\"fields.hasErrors('<path>') ? '<classes>'\"`"
            + " with the element's `t:field` path, merged into any existing `t:classappend`");
  }

  /** #822: the exact forms with a 1:1 Thwart spelling are rewritten; the rest stay reported. */
  @Test
  void secAuthorizeAttrAndErrorClassExactFormsAreRewritten() throws Exception {
    String src = "<form t:params=\"Owner o, String x\" t:object=\"${o}\">"
        + "<p sec:authorize=\"hasRole('ADMIN')\">a</p><p sec:authorize=\" isAuthenticated() \">b</p>"
        + "<p sec:authorize=\"hasAnyAuthority('P_A', 'P_B')\">c</p>"
        + "<a th:attr=\"data-lightbox=${x},aria-expanded=${#strings.isEmpty(x)}\">d</a>"
        + "<input th:errorclass=\"has-error\" th:field=\"*{name}\"/>"
        + "<input th:field=\"*{city}\" th:errorclass=\"'is-invalid x'\"></form>";
    assertEquals("<form t:params=\"Owner o, String x\" t:object=\"o\">"
        + "<p t:if=\"auth.hasRole('ADMIN')\">a</p><p t:if=\"auth.isAuthenticated()\">b</p>"
        + "<p t:if=\"auth.hasAnyAuthority('P_A', 'P_B')\">c</p>"
        + "<a t:data-lightbox=\"x\" t:aria-expanded=\"strings.isEmpty(x)\">d</a>"
        + "<input t:classappend=\"fields.hasErrors('name') ? 'has-error'\" t:field=\"*{name}\"/>"
        + "<input t:field=\"*{city}\" t:classappend=\"fields.hasErrors('city') ? 'is-invalid x'\"></form>",
        convert(src));
    // Each sec:authorize rewrite names the RoleHierarchy/prefix caveats of guide section 4.10.
    String rewritten = "1: rewritten: `sec:authorize` to `t:if=\"auth.";
    assertMessages(src, rewritten + "hasRole('ADMIN')\"`", rewritten + "isAuthenticated()\"`",
        rewritten + "hasAnyAuthority('P_A', 'P_B')\"`");
    assertTrue(messages(src).get(0).contains("RoleHierarchy"));
    // A model variable named auth is still a root that gets a t:params stub; only auth.f( is the namespace.
    assertTrue(convert("<p th:text=\"${auth.user.name}\">x</p>").contains("Object auth"));
  }

  @Test
  void secAuthorizeAttrAndErrorClassWithoutAnExactFormAreReported() throws Exception {
    for (String tag : List.of("<p sec:authorize=\"isAnonymous()\">", "<p sec:authorize=\"hasRole(${r})\">",
        "<p sec:authorize=\"hasRole('A') and hasRole('B')\">", "<p th:if=\"${x}\" sec:authorize=\"hasRole('A')\">",
        "<p th:attr=\"title=${x}\">", "<p th:attr=\"data-A=${x}\" t:data-a=\"x\">", "<p th:attr=\"data-a=${x} + 'b'\">", "<p th:attr=\"data-a=${T(X).y}\">",
        "<p th:attr=\"data-a=${x}\" th:data-a=\"${x}\">", "<input th:errorclass=\"err\">",
        "<input th:field=\"*{a}\" th:classappend=\"${x}\" th:errorclass=\"err\">",
        "<input th:field=\"*{a[0]}\" th:errorclass=\"err\">", "<input th:field=\"*{a}\" th:errorclass=\"${e}\">")) {
      String src = "<div t:params=\"String x, String r, String e\">" + tag + "</p></div>";
      String out = convert(src);
      assertTrue(out.contains("sec:authorize") || out.contains("th:attr") || out.contains("th:errorclass"), out);
      assertTrue(messages(src).stream().anyMatch(m -> m.contains("dialect") || m.contains("not supported")), tag);
    }
  }

  @Test
  void preprocessedPathSegmentsBecomePathVariables() throws Exception {
    String src = "<p t:params=\"Owner owner, Pet pet\"><a th:href=\"@{/owners/__${owner.id}__/edit}\">a</a>"
        + "<a th:href=\"@{__${owner.id}__/pets/__${pet.id}__/edit}\">b</a><a th:href=\"@{/o/__${ owner.id }__}\">c</a></p>";
    assertEquals("<p t:params=\"Owner owner, Pet pet\"><a t:href=\"@{/owners/{ownerId}/edit(ownerId=owner.id)}\">a</a>"
        + "<a t:href=\"@{{ownerId}/pets/{petId}/edit(ownerId=owner.id, petId=pet.id)}\">b</a>"
        + "<a t:href=\"@{/o/{ownerId}(ownerId=owner.id)}\">c</a></p>", convert(src));
    // Not a property path, no literal '/', a query or a name clash: left in place and reported.
    for (String link : List.of("@{__${link}__}", "@{/f/__${#locale}__.png}", "@{/u?id=__${u.id}__}",
        "@{/a/__${a.bC}__/__${aB.c}__}", "@{/a/__${x}__(p=1)}")) {
      String one = "<a th:href=\"" + link + "\">x</a>";
      assertEquals("<a t:href=\"" + link + "\">x</a>", convert(one).replaceFirst(" t:params=\"[^\"]*\"", ""), link);
      assertTrue(messages(one).stream().anyMatch(m -> m.contains("preprocessing")), link);
    }
  }

  @Test
  void renamesNamespaceAttributesAndBlocks() throws Exception {
    assertEquals(
        "<html xmlns:t=\"https://thwart.chainguard.dev\"><t:block t:if=\"ok\">x</t:block><t:block t:insert=\"f\"/></html>",
        convert("<html xmlns:th=\"http://www.thymeleaf.org\"><th:block th:if=\"${ok}\">x</th:block>"
            + "<th:block th:insert=\"${f}\"/></html>")
            .replace(" t:params=\"Object ok, Object f\"", ""));
  }

  @Test
  void literalValuedAttributesAreOnlyRenamed() throws Exception {
    assertEquals("<div><p t:remove=\"all\">x</p></div>", convert("<div><p th:remove=\"all\">x</p></div>"));
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
  void eachAndWithRewriteEachExpression() throws Exception {
    assertEquals("<ul t:params=\"Object items, Object b\"><li t:each=\"it, st : items\" t:with=\"x=it.a, y=b\""
            + " t:data-i=\"st.index\" t:text=\"x + y + st.count\"></li></ul>",
        convert("<ul><li th:each=\"it, st : ${items}\" th:with=\"x=${it.a}, y=${b}\""
            + " th:data-i=\"${st.index}\" th:text=\"${x + y + st.count}\"></li></ul>"));
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
        + " (`Fragment` for markup arguments such as `~{::body}`); every call passes every argument, so pass `null`"
        + " where a Thymeleaf call omitted one; a fragment sees only its parameters, so add one for each caller"
        + " variable it reads (guide section 4.8)"), messages(src));
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
    assertEquals("<html xmlns:t=\"https://thwart.chainguard.dev\""
            + " t:params=\"Object form, Object owner, Object query, Object user, Object t, Object count, Object html\">",
        convert(src).lines().findFirst().orElseThrow());
    assertMessages(src, "2: link `@{/o/{id}(id=owner.id, q=query)}` fails the render if a path variable is `null`",
        "1: `form` is declared as `Object`", "1: `owner` is declared as `Object`",
        "1: `query` is declared as `Object`", "1: `user` is declared as `Object`",
        "1: `t` is declared as `Object`", "1: `count` is declared as `Object`",
        "1: `html` is declared as `Object`");
  }

  // ---- compiler rules are not mirrored (#593, #595) ----

  /**
   * Forms the compiler rejects with file:line:col and the fix are converted like any other value
   * (or left as they are) and not reported: the compiler's error is the single source (#595).
   * Each case is {source, converted}; the compiler's errors for them are pinned in
   * {@code MigrationRulesCompilerTest}.
   */
  @Test
  void formsTheCompilerRejectsAreConvertedWithoutAReport() throws Exception {
    String[][] cases = {
      // event handler and style: renamed, so the compiler's t:onclick and t:style errors name the fix
      {"<div t:params=\"String s\"><b th:onclick=\"${s}\" th:onMouseOver=\"${s}\" th:style=\"${s}\">x</b></div>",
       "<div t:params=\"String s\"><b t:onclick=\"s\" t:onMouseOver=\"s\" t:style=\"s\">x</b></div>"},
      // CSS attributes of an SVG element
      {"<div t:params=\"String s\"><svg th:cursor=\"${s}\"><rect th:fill=\"${s}\" th:stroke=\"${s}\"/>"
           + "<RECT TH:FILL=\"${s}\"/></svg></div>",
       "<div t:params=\"String s\"><svg t:cursor=\"s\"><rect t:fill=\"s\" t:stroke=\"s\"/>"
           + "<RECT t:FILL=\"s\"/></svg></div>"},
      // a computed target that is not a choice between literals
      {"<div t:params=\"String s\"><a th:target=\"${s}\">a</a></div>",
       "<div t:params=\"String s\"><a t:target=\"s\">a</a></div>"},
      // raw output of a String
      {"<div t:params=\"String s\"><p th:utext=\"${s}\"></p><p>[(${s})]</p></div>",
       "<div t:params=\"String s\"><p t:utext=\"s\"></p><p>[(${s})]</p></div>"},
      // a POST form's computed action that is not a link
      {"<div t:params=\"String s\"><form method=\"post\" t:action=\"s\"></form>"
           + "<form t:action=\"s\" METHOD=\"Post\"></form></div>", null},
      // [[...]] in a template literal, a regular expression, a comment, a JSON string
      {"<div t:params=\"String s\"><script>var a = `[[${s}]]`; var b = `a ${a} [[${s}]]`;\n"
           + "var r = /a[[${s}]]/; // [[${s}]]\n</script>"
           + "<script type=\"application/json\">{\"a\": \"x [[${s}]]\"}</script></div>", null},
    };
    for (String[] c : cases) {
      assertEquals(c[1] == null ? c[0] : c[1], convert(c[0]), c[0]);
      assertEquals(List.of(), messages(c[0]), c[0]);
    }
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
    String src = "<div>\n\n<input th:field=\"*{__${name}__}\"/><a th:href=\"@{/x/__${i + 1}__}\">x</a>"
        + "<a th:href=\"@{'/x?p=__${i}__&q=1'}\">y</a></div>";
    assertEquals(src.replace("th:", "t:"), convert(src));
    assertMessages(src,
        "3: dynamic form-field path `*{__...__}` is not supported (preprocessing); pass the path to a"
            + " fragment's `Field` parameter (guide section 4.8, Reusable form fields)",
        "3: `__...__` preprocessing is not supported",
        "3: `__...__` preprocessing is not supported");
  }

  /** #857: a whole link from a variable gets the rewrite that works, not "inline the dynamic part". */
  @Test
  void preprocessedWholeLinkPointsAtPassingTheLink() throws Exception {
    assertMessages("<a t:params=\"String link\" th:href=\"@{__${link}__}\">x</a>",
        "1: `__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field;"
            + " for a whole link `@{__${link}__}`, pass the link itself (`@{/owners}` from the caller) and write"
            + " `t:href=\"link\"`");
  }

  /** #857: since #851 a path that is one quoted literal is a constant link and compiles. */
  @Test
  void quotedConstantLinksAreConvertedWithoutANote() throws Exception {
    String src = "<p><a th:href=\"@{'/owners?page=1'}\">a</a><a th:href=\"@{ '/owners/{id}'(id=1)}\">b</a></p>";
    assertEquals(src.replace("th:", "t:"), convert(src));
    assertMessages(src);
  }

  /** #821: {@code ${{...}}} formats with the conversion service; dropping its {@code $} changed its meaning. */
  @Test
  void conversionServiceFormattingIsLeftInPlaceAndReported() throws Exception {
    String src = "<div t:params=\"Order order\"><p th:text=\"${{order.date}}\">d</p><p th:text=\"${order.date}\">d</p></div>";
    assertEquals("<div t:params=\"Order order\"><p t:text=\"${{order.date}}\">d</p><p t:text=\"order.date\">d</p></div>",
        convert(src));
    assertEquals(List.of("1: `${{...}}` conversion-service formatting is not supported; format the value in the view type"),
        messages(src));
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
        + "<a th:href=\"@{{id}/edit(id=${owner.id})}\">c</a></p>";
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
    // petclinic ownerDetails.html (#435): Thwart's *{...} holds one property path.
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
        "1: `*{pets[0].name + ' ' + lastName}` is not converted: in Thwart `*{...}` holds one property path",
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
        "1: `th:action` = `u`: for a `null` value Thymeleaf wrote `action=\"\"`, but Thwart omits the attribute;"
            + " if it can be `null` and the empty attribute matters, write `x ?: ''` for a `String`",
        "1: `th:name` = `owner.field`: for a `null` value Thymeleaf wrote `name=\"\"`",
        "2: `th:href` = `owner.url`: for a `null` value Thymeleaf wrote `href=\"\"`",
        "2: `th:src` = `owner.photo`: for a `null` value Thymeleaf wrote `src=\"\"`",
        "2: `th:type` = `owner.kind`: for a `null` value Thymeleaf wrote `type=\"\"`");
  }

  @Test
  void attributesThatCannotBeNullOrThatThymeleafOmitsAreNotReported() throws Exception {
    assertMessages("<form t:params=\"Owner owner\" th:action=\"@{/owners/new}\">"
        + "<input th:value=\"'x'\" th:name=\"|f${owner.id}|\"><input th:value=\"42\" th:type=\"#{input.type}\">"
        + "<input th:title=\"${owner.name}\"><input t:value=\"owner.name\">"
        + "<input th:value=\"checkbox\"><input th:value=\"${owner.name}\"></form>"); // #857: value is not reported
  }

  @Test
  void literalTokensAreQuoted() throws Exception {
    assertEquals("<ul t:params=\"Object nav_x\"><li t:class=\"'nav-item'\" t:text=\"'item.name'\" t:value=\"42\""
            + " t:if=\"true\" t:with=\"c=nav_x\" t:data-k=\"'a-b'\">x</li></ul>",
        convert("<ul><li th:class=\"nav-item\" th:text=\"item.name\" th:value=\"42\" th:if=\"true\""
            + " t:with=\"c=nav_x\" th:data-k=\"a-b\">x</li></ul>"));
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
  void fragmentReferencesSelectionsAndThwartValuesAreNotLiteralTokens() throws Exception {
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
        "1: `#request` reads the servlet request, which a Thwart template cannot see",
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
        "2: `session.user` reads the HTTP session, which a Thwart template cannot see; in the controller add `user`"
            + " to the model (`@SessionAttribute(name = \"user\", required = false)` on the handler method, or a"
            + " `@ModelAttribute(\"user\")` method in a `@ControllerAdvice` for a value every page needs), declare it in"
            + " `t:params` and read it as `user`",
        "3: `param.error` reads a request parameter, which a Thwart template cannot see; in the controller take it"
            + " as `@RequestParam(name = \"error\", required = false) String error`, add it to the model",
        "4: `application.version` reads an application (servlet context) attribute",
        "5: `_csrf.token` reads Spring Security's CSRF token request attribute, which a Thwart template cannot see;"
            + " the starter already adds the `_csrf` hidden input to every same-origin POST form, so delete a"
            + " hand-written one; for JavaScript (htmx, `fetch`) add the token (`CsrfToken.getToken()`) to the model from"
            + " a `@ModelAttribute(\"token\")` method in a `@ControllerAdvice`, declare it in `t:params` and read it as"
            + " `token` (guide section 1) in a script declaration (`var token = [[${token}]];`, guide section 4.4), not a"
            + " `<meta>`",
        "6: `param['q']` reads a request parameter, which a Thwart template cannot see; in the controller take it"
            + " as `@RequestParam(name = \"q\"",
        "6: `session` reads the HTTP session, which a Thwart template cannot see; in the controller add `value`",
        "1: `owner` is declared as `Object`");
  }

  /** #820: PetClinic's visit form binds {@code visit}, and a later loop reuses the name for each past visit. */
  @Test
  void formObjectIsAParameterEvenIfALaterLoopReusesItsName() throws Exception {
    String src = "<div><form th:object=\"${visit}\"><input th:field=\"*{date}\"></form>"
        + "<p th:each=\"visit : ${pet.visits}\" th:text=\"${visit.date}\"></p>"
        + "<p th:each=\"v : ${pet.visits}\"><form th:object=\"${v}\"></form></p>"
        + "<tr th:object=\"${item}\" th:each=\"item : ${pet.items}\"></tr></div>";
    assertTrue(convert(src).startsWith("<div t:params=\"Object visit, Object pet\">"), convert(src));
  }

  @Test
  void ambientExpressionObjectsAreReportedWithAFixIt() throws Exception {
    String src = "<div><p th:text=\"${#httpServletRequest.requestURI}\"></p>"
        + "<p th:text=\"${#session.getAttribute('u')}\"></p><p th:text=\"${#servletContext.contextPath}\"></p>"
        + "<p th:text=\"${#vars['x']}\"></p><p th:text=\"${#execInfo.templateName}\"></p>"
        + "<a th:href=\"${#response.encodeURL('/a')}\"></a><p th:text=\"${#requests.x}\"></p></div>";
    assertEquals(src.replace("th:", "t:"), convert(src));
    assertMessages(src,
        "1: `#httpServletRequest.requestURI` reads the servlet request, which a Thwart template cannot see;"
            + " compute the value in the controller, add it to the model and declare it in `t:params` (a value every"
            + " page needs, such as the request URI, comes from a `@ModelAttribute` method",
        "1: `#session` reads the HTTP session",
        "1: `#servletContext.contextPath` reads the servlet context",
        "1: `#vars` reads Thymeleaf's evaluation context",
        "1: `#execInfo.templateName` reads Thymeleaf's execution info",
        "1: `#response` uses the servlet response",
        "1: Thymeleaf utility object `#requests` has no Thwart equivalent");
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
  void unsupportedAttributesInlineAndDialectsAreNotRenamed() throws Exception {
    String src = "<div xmlns:sec=\"x\">\n<b th:styleappend=\"${s2}\" th:inline=\"javascript\""
        + " sec:authorize=\"hasIpAddress('A')\" layout:fragment=\"c\" data-th-text=\"${t}\" th:include=\"f\">x</b></div>";
    assertEquals(src, convert(src));
    assertMessages(src,
        "2: `th:styleappend` is not supported; merge it into one `t:style` from literals, numbers and"
            + " `css.color(...)`/`css.length(...)`/`css.url(...)`",
        "2: `th:inline` is not supported",
        "2: `sec:authorize` belongs to the `sec:` dialect",
        "2: `layout:fragment` belongs to the `layout:` dialect",
        "2: `data-th-text` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:text`",
        "2: `th:include` is not supported; use `t:insert` or `t:replace`");
  }

  @Test
  void unsupportedThymeleafAttributesAreNotRenamed() throws Exception {
    // Renamed to t:, these compiled as ordinary attributes and the check or merge was lost (#711).
    String src = "<p th:assert=\"${b}\" th:attrappend=\"class=${s}\" th:attrprepend=\"class=${s}\"\n"
        + " th:alt-title=\"${t}\" th:lang-xmllang=\"${l}\">x</p>";
    assertEquals(src, convert(src));
    assertMessages(src,
        "1: `th:assert` is not supported; check it in the controller (or guard the element with `t:if`)",
        "1: `th:attrappend` is not supported; use `t:classappend`, or one `t:<attr>` with a `|...|` literal",
        "1: `th:attrprepend` is not supported; use `t:classappend`, or one `t:<attr>` with a `|...|` literal",
        "2: `th:alt-title` is not supported; write `t:alt` and `t:title`",
        "2: `th:lang-xmllang` is not supported; write `t:lang`");
  }

  @Test
  void unsupportedConstructsInInlinedTextAreReported() throws Exception {
    String src = "<p>[[${T(System).getenv()}]]</p>";
    assertEquals(src, convert(src));
    assertMessages(src, "1: `T(...)` static class access is not supported");
  }

  // ---- quoted inlines in <script> (#282) ----

  private static final String UNQUOTED = "was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes";

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
  void templateLiteralHoldingOnlyAnInlineIsNotUnquoted() throws Exception {
    // Dropping the backticks would turn a tag call String.raw`...` into String.raw"value", so a
    // template literal is never unquoted, tagged or not; the compiler's error says what to write.
    for (String body : List.of("var a = `[[${x}]]`;", "`[[${x}]]`", "function f() { return `[[${x}]]`; }",
        "var a = String.raw`[[${x}]]`;", "var a = tag /* c */ `[[${x}]]`;", "var a = f()`[[${x}]]`;")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src);
    }
    // A quoted inline inside template code is still unquoted.
    String nested = script("", "var a = `${\"[[${x}]]\"}`;");
    assertEquals(script("", "var a = `${[[${x}]]}`;"), convert(nested));
    assertMessages(nested, "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
  }

  // ---- the script prelude (#540): values outside it are left for the compiler ----

  @Test
  void valueOutsideThePreludeIsNotRewritten() throws Exception {
    // The compiler rejects each with the fix (#540, #595).
    for (String body : List.of("var d = [[${x}]].split(\".\");", "init([[${x}]], 2);",
        "var a = [[${x}]]; f(a); var b = [[${y}]];", "$(function () { remove([[${x}]]); });",
        "'use strict';\nf([[${x}]]);", "var a = `${[[${x}]]}`;", "var r = /a[[${x}]]/g;")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src);
    }
  }

  @Test
  void naturalTemplateDefaultsAndUnquotedStringsAreRewritten() throws Exception {
    String unquoted = script("", "var a = \"[[${x}]]\" / 2;\nvar b = [[${y}]];");
    assertEquals(script("", "var a = [[${x}]] / 2;\nvar b = [[${y}]];"), convert(unquoted));
    assertMessages(unquoted, "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
    String natural = script(JS, "var a = /*[[${x}]]*/ {\"k\": [1,\n 2]};\nvar r = /a/;\nvar b = [[${y}]];");
    assertEquals(script(JS, "var a = [[${x}]];\nvar r = /a/;\nvar b = [[${y}]];"), convert(natural));
    assertMessages(natural, TH_INLINE, "1: `/*[[${x}]]*/ {");
    // The text Thymeleaf drops after a natural-template comment it reports stays as it is.
    String manual = script(JS, "var a = /*[[${x}]]*/ [[[${y}]], 1 + 2];");
    assertEquals(manual, convert(manual));
    // Not a JavaScript script, or th:inline="none": not rewritten.
    for (String src : List.of(script(" type=\"text/x-template\"", "var r = /a/; [[${x}]]"),
        script(" type=\"application/json\"", "{\"a\": [1, [[${x}]]]}"),
        "<script th:inline=\"none\">var r = /a/; [[${x}]]</script>")) {
      assertEquals(src, convert(src), src);
      assertEquals(src.contains("none") ? List.of("1: `th:inline` is not supported; use `[[...]]` in `<script>`"
          + " (JSON-escaped) or put data in a `<script type=\"application/json\">` block") : List.of(), messages(src), src);
    }
  }

  @Test
  void stringsAroundAnUnquotedInlineAreKept() throws Exception {
    // Exactly one inline between two quotes is a whole string literal, even between concatenations.
    String src = script("", "var a = 'a' + \"[[${x}]]\" + 'b';");
    assertEquals(script("", "var a = 'a' + [[${x}]] + 'b';"), convert(src));
    assertMessages(src, "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
    // Quotes inside the expression don't end or start a string.
    String quotes = script("", "var a = [[${x == '\"'}]]; var b = \"[[${y + 'a'}]]\";");
    assertEquals(script("", "var a = [[${x == '\"'}]]; var b = [[${y + 'a'}]];"), convert(quotes));
    assertMessages(quotes, "1: `\"[[${y + 'a'}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void inlineSharingAStringWithOtherTextIsLeftForTheCompiler() throws Exception {
    // The compiler rejects an inline inside a string, with the fix (#595).
    for (String body : List.of("var a = \"Hello [[${x}]]!\";", "var a = \"[[${x}]] [[${y}]]\";",
        "var a = \"\\\"[[${x}]]\";", "var a = `a ${1} [[${x}]]`;")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src);
    }
    String json = script(" type=\"application/json\"", "{\"a\": \"Hello [[${x}]]\"}");
    assertEquals(json, convert(json));
    assertMessages(json);
  }

  @Test
  void quotedInlineIsReportedOnItsOwnLine() throws Exception {
    String src = "<div t:params=\"String x\">\n<script>\n  var a = 1;\n  var b = \"x [[${x}]]\";\n"
        + "  var c = \"[[${x}]]\";\n</script></div>";
    assertEquals("<div t:params=\"String x\">\n<script>\n  var a = 1;\n  var b = \"x [[${x}]]\";\n"
        + "  var c = [[${x}]];\n</script></div>", convert(src));
    assertMessages(src, "5: `\"[[${x}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void inlineInsideAJavaScriptCommentIsNotRewritten() throws Exception {
    // The compiler rejects [[...]] in a comment (#595); the natural-template rewrite is #315.
    for (String body : List.of(
        "var a = /*[[${x}]]*/ 'd';",
        "// \"[[${x}]]\"\nvar a = 1;",
        "/* \"[[${x}]]\" */ var a = 1;",
        "/*/ \"[[${x}]]\" */",
        "var a = 1; // [[${x}]]",
        "var a = 1; /* [[${x}]]\n [[${y}]] */")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src);
    }
  }

  // ---- natural-template comments in th:inline="javascript" scripts (#315) ----

  private static final String TH_INLINE = "1: `th:inline` is not supported";
  private static final String JS = " th:inline=\"javascript\"";

  @Test
  void naturalTemplateCommentIsRewrittenToItsInline() throws Exception {
    // Each expected output is what thymeleaf 3.1.3 renders for the input, with the inline in place
    // of its value (checked by hand against Thymeleaf; it is not a dependency of thwart-migrate).
    String[][] cases = {
        {"var a = /*[[${x}]]*/ \"d\";", "var a = [[${x}]];", "`/*[[${x}]]*/ \"d\"`", "the default `\"d\"`"},
        {"f(/*[[${x}]]*/ null);", "f([[${x}]]);", "`/*[[${x}]]*/ null`", "the default `null`"},
        {"var a = /*[[${x}]]*/ -1.5e3;", "var a = [[${x}]];", "`/*[[${x}]]*/ -1.5e3`", "the default `-1.5e3`"},
        {"var a = /*[[${x}]]*/ 'x/y';", "var a = [[${x}]];", "`/*[[${x}]]*/ 'x/y'`", "the default `'x/y'`"},
        {"var a = /*[[${x}]]*/ [ 'a;b' ];", "var a = [[${x}]];", "`/*[[${x}]]*/ [ 'a;b' ]`", "the default"},
        {"var a = /*[[${x}]]*/ {\"k\": [1,\n 2]};\nvar c;", "var a = [[${x}]];\nvar c;",
            "`/*[[${x}]]*/ {\"k\": [1,\n 2]}`", "the default"},
        {"var a = /*[[${x}]]*/;", "var a = [[${x}]];", "`/*[[${x}]]*/`", "no default"},
        {"var a = /*[[${x}]]*/\nvar b;", "var a = [[${x}]]\nvar b;", "`/*[[${x}]]*/`", "no default"},
        {"var a = /*[[${x}]]*/ 'd'", "var a = [[${x}]]", "`/*[[${x}]]*/ 'd'`", "the default `'d'`"},
        {"var a = /*[[${x}]]*/ 'd' /* c */;", "var a = [[${x}]]/* c */;", "`/*[[${x}]]*/ 'd'`", "the default"},
        {"var a = /*[[${x}]]*/ 'd' // c\nvar b;", "var a = [[${x}]]// c\nvar b;", "`/*[[${x}]]*/ 'd'`", "the default"},
    };
    for (String[] c : cases) {
      String src = script(JS, c[0]);
      assertEquals(script(JS, c[1]), convert(src), c[0]);
      assertMessages(src, TH_INLINE, "1: " + c[2] + " in a script was rewritten to `[[${x}]]`: Thymeleaf's natural"
          + " template wrote the value in place of the comment and dropped " + c[3]);
    }
    String two = script(JS, "var a = /*[[${x}]]*/ 0, b = /*[[${y}]]*/ true;");
    assertEquals(script(JS, "var a = [[${x}]], b = [[${y}]];"), convert(two));
    assertMessages(two, TH_INLINE, "1: `/*[[${x}]]*/ 0` in a script was rewritten",
        "1: `/*[[${y}]]*/ true` in a script was rewritten");
    // A JSON script, and th:inline="javascript" on an ancestor.
    String json = script(JS + " type=\"application/json\"", "{\"a\": /*[[${x}]]*/ \"d\"}");
    assertEquals(script(JS + " type=\"application/json\"", "{\"a\": [[${x}]]}"), convert(json));
    String ancestor = "<div th:inline=\"javascript\" t:params=\"String x\"><script>var a = /*[[${x}]]*/ 'd';</script></div>";
    assertEquals(ancestor.replace("/*[[${x}]]*/ 'd'", "[[${x}]]"), convert(ancestor));
    assertMessages(ancestor, TH_INLINE, "1: `/*[[${x}]]*/ 'd'` in a script was rewritten");
    // [(...)] the same way; Thwart rejects it in a script, which the report says.
    String raw = script(JS, "var a = /*[(${y})]*/ 0;");
    assertEquals(script(JS, "var a = [(${y})];"), convert(raw));
    assertMessages(raw, TH_INLINE, "1: `/*[(${y})]*/ 0` in a script was rewritten to `[(${y})]`");
    assertTrue(messages(raw).get(1).endsWith("; `[(...)]` is a build error in a Thwart script: write `[[...]]`,"
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
      assertMessages(src, attributes.isEmpty() ? new String[0] : new String[] {TH_INLINE});
    }
    // Not the whole comment, in a line comment, or in a string: Thymeleaf leaves them alone too.
    for (String body : List.of("var a = /* [[${x}]] */ 'd';", "var a = /*[[${x}]] */ 'd';",
        "// /*[[${x}]]*/ 'd'\nvar a = 1;")) {
      String src = script(JS, body);
      assertEquals(src, convert(src), body);
      assertMessages(src, TH_INLINE);
    }
    String string = script(JS, "var a = '/*[[${x}]]*/ d';");
    assertEquals(string, convert(string));
    assertMessages(string, TH_INLINE);
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
        "// a\n  const a = [[${x}]];\n  let b = [[${y}]]; f(a, b);",
        // Outside the prelude too: the compiler reports them (#540).
        "var a = \"a\" + [[${x}]] + \"b\";",
        "var re = \"a/b`\"; var a = [[${x}]].length;",
        "var a = \"done\"; var b = [[${x}]]; var c = 'it\\'s';",
        "var a = \"unterminated\nvar b = [[${x}]];")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertEquals(List.of(), messages(src), body);
    }
    for (String src : List.of(
        "<div t:params=\"String x\"><p>\"[[${x}]]\"</p><b title=\"'[[${x}]]'\" data-f=\"f('[[${x}]]')\">b</b></div>",
        script(" type=\"text/x-template\"", "\"[[${x}]]\""),
        script(" type=\"text/x-template\"", "a") + "<p>'[[${x}]]'</p>")) {
      assertEquals(src, convert(src), src);
      assertEquals(List.of(), messages(src), src);
    }
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
    // U+2028 and U+2029 don't end a string, and "\" + CRLF is a line continuation: the string
    // after the first one is read as a string, and unquoted.
    for (String first : List.of("x ", "x ", "line \\\r\n", "line \\\n", "line \\\r")) {
      String body = "var a = \"" + first + "\"; var b = \"[[${x}]]\";";
      String src = script("", body);
      assertEquals(script("", body.replace("\"[[${x}]]\"", "[[${x}]]")), convert(src), body);
      assertMessages(src, (body.contains("\n") ? "2" : "1") + ": `\"[[${x}]]\"` in a script " + UNQUOTED);
    }
    // A bare line break still ends an unterminated string.
    String bare = script("", "var a = \"x\n\"; var b = \"[[${x}]]\";");
    assertEquals(bare, convert(bare));
    assertMessages(bare);
  }

  @Test
  void quotesInsideTheInlineExpressionDoNotEndIt() throws Exception {
    // A '...' string in the expression may hold "]]"; the end is found the way the compiler finds it.
    String whole = "<div t:params=\"Object m\"><script>var k = \"[[${m['k]]']}]]\";</script></div>";
    assertEquals("<div t:params=\"Object m\"><script>var k = [[${m['k]]']}]];</script></div>", convert(whole));
    assertMessages(whole, "1: `\"[[${m['k]]']}]]\"` in a script " + UNQUOTED);
    String part = "<div t:params=\"Object m\"><script>var r = \"a [[${m['k]]']}]]\";</script></div>";
    assertEquals(part, convert(part));
    assertMessages(part);
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
