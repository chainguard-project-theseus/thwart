package dev.tessera.migrate;

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

  // ---- attribute and element names ----

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
    assertMessages(src, "2: `[(...)]` prints `html` without escaping",
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
    assertEquals(List.of("1: `T(...)` static class access is not supported (never); expose the value through"
        + " a view type or a `@TemplateFunction`"), messages(src));
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
        "1: Thymeleaf utility object `#request` has no Tessera equivalent",
        "1: Thymeleaf utility object `#ctx` has no Tessera equivalent",
        "1: collection selection/projection is not supported",
        "1: link with a non-literal path");
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

  @Test
  void unsupportedConstructsInInlinedTextAreReported() throws Exception {
    String src = "<p>[[${T(System).getenv()}]]</p>";
    assertEquals(src, convert(src));
    assertMessages(src, "1: `T(...)` static class access is not supported");
  }

  @Test
  void literalTextThatLooksDangerousIsNotReported() throws Exception {
    String src = "<p th:text=\"'T(x) new Foo @bean #ctx.x'\">x</p>";
    assertEquals("<p t:text=\"'T(x) new Foo @bean #ctx.x'\">x</p>", convert(src));
    assertEquals(List.of(), messages(src));
  }
}
