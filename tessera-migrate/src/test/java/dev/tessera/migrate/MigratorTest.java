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
  void reportsAMissingSpaceBetweenAttributes() throws Exception {
    assertMessages("<a href=\"/a\"class=\"x\" title='t'id=\"i\" data-x=y lang=\"en\">a</a>\n",
        "1: no space before attribute `class`", "1: no space before attribute `id`");
    assertMessages("<a href=\"/a\" class=\"x\">a</a><b title=\"t\"\n>b</b>\n");
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
  private static final String IN_STRING =
      "inside a quoted JavaScript string is a build error in Tessera (it writes a JSON value with its own quotes)";

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
          + " tagged template keep them and write ``tag`${[[...]]}` ``");
    }
    // A quoted inline inside template code is still unquoted.
    String nested = script("", "var a = `${\"[[${x}]]\"}`;");
    assertEquals(script("", "var a = `${[[${x}]]}`;"), convert(nested));
    assertMessages(nested, "1: `\"[[${x}]]\"` in a script " + UNQUOTED);
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
  void inlineSharingAStringWithOtherTextIsReportedNotRewritten() throws Exception {
    for (String body : List.of(
        "var a = \"Hello [[${x}]]!\";",
        "var a = 'Hello [[${x}]]';",
        "var a = \"[[${x}]]!\";",
        "var a = \"\\\"[[${x}]]\";",
        "var a = `a ${1} [[${x}]]`;",
        "var a = `${\"a [[${x}]]\"}`;")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src, "1: `[[${x}]]` " + IN_STRING + "; rewrite as `\"text \" + [[${x}]] + \" text\"`");
    }
    String two = script("", "var a = \"[[${x}]] [[${y}]]\";");
    assertEquals(two, convert(two));
    assertMessages(two, "1: `[[${x}]]` " + IN_STRING, "1: `[[${y}]]` " + IN_STRING);
  }

  @Test
  void quotedInlineIsReportedOnItsOwnLine() throws Exception {
    String src = "<div t:params=\"String x\">\n<script>\n  var a = 1;\n  var b = \"x [[${x}]]\";\n"
        + "  var c = \"[[${x}]]\";\n</script></div>";
    assertMessages(src, "4: `[[${x}]]` " + IN_STRING, "5: `\"[[${x}]]\"` in a script " + UNQUOTED);
  }

  @Test
  void inlineInsideAJavaScriptCommentIsReportedNotRewritten() throws Exception {
    // The compiler rejects [[...]] in a comment (#281); the natural-template rewrite is #315.
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

  @Test
  void inlinesOutsideJavaScriptStringsAreUnchanged() throws Exception {
    for (String body : List.of(
        "var a = [[${x}]];",
        "var a = \"a\" + [[${x}]] + \"b\";",
        "var a = \"x\u2028\"; var b = [[${x}]];",
        "var a = `${[[${x}]]}`;",
        "var a = `${ {b: \"}\"} }` + [[${x}]];",
        "var a = \"done\"; var b = [[${x}]]; var c = 'it\\'s';",
        "var a = \"unterminated\nvar b = [[${x}]];")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertEquals(List.of(), messages(src), body);
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
    assertEquals(List.of(), messages(dynamic));
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
    for (String body : List.of("var a = \"x\u2028 [[${x}]]\";", "var a = \"x\u2029 [[${x}]]\";",
        "var a = \"line \\\r\n[[${x}]]\";", "var a = \"line \\\n[[${x}]]\";", "var a = \"line \\\r[[${x}]]\";")) {
      String src = script("", body);
      assertEquals(src, convert(src), body);
      assertMessages(src, (body.contains("\n") ? "2" : "1") + ": `[[${x}]]` " + IN_STRING);
    }
    // A bare line break still ends an unterminated string.
    String bare = script("", "var a = \"x\n[[${x}]]\";");
    assertEquals(bare, convert(bare));
    assertEquals(List.of(), messages(bare));
  }

  @Test
  void quotesInsideTheInlineExpressionDoNotEndIt() throws Exception {
    // A '...' string in the expression may hold "]]"; the end is found the way the compiler finds it.
    String whole = "<div t:params=\"Object m\"><script>var k = \"[[${m['k]]']}]]\";</script></div>";
    assertEquals("<div t:params=\"Object m\"><script>var k = [[${m['k]]']}]];</script></div>", convert(whole));
    assertMessages(whole, "1: `\"[[${m['k]]']}]]\"` in a script " + UNQUOTED);
    String part = "<div t:params=\"Object m\"><script>var r = \"a [[${m['k]]']}]]\";</script></div>";
    assertEquals(part, convert(part));
    assertMessages(part, "1: `[[${m['k]]']}]]` " + IN_STRING + "; rewrite as `\"text \" + [[${m['k]]']}]] + \" text\"`");
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
