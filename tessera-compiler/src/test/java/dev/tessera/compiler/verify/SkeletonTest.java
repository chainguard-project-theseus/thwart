package dev.tessera.compiler.verify;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.testing.TestFragments;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Messages;
import dev.tessera.runtime.RenderContext;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The skeleton (#366): what it records for the markup the generator makes up and for each emission
 * path, and that the render-membership check of {@code Compilation.render} fails on a skeleton
 * that differs from the output. Every render below is also checked against its skeleton by
 * {@code Compilation.render} itself.
 */
class SkeletonTest {
  private static final String NS = "xmlns:t=\"https://tessera.dev\"";
  private static final String FORM_HEAD =
      "<form " + NS + " t:import=\"com.example.*\" t:params=\"Owner owner, Boolean off\" t:object=\"owner\"";

  /** A Forms with a CSRF token and errors on firstName. */
  private static final class StubForms implements Forms {
    final List<String> errors = new ArrayList<>();
    Forms.CsrfToken token;

    @Override
    public String value(String objectName, String path, Object currentValue) {
      return currentValue == null ? "" : String.valueOf(currentValue);
    }

    @Override
    public List<String> errors(String objectName, String path) {
      return errors;
    }

    @Override
    public Optional<Forms.CsrfToken> csrfToken() {
      return Optional.ofNullable(token);
    }
  }

  private static RenderContext ctx(Forms forms) {
    return new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), forms);
  }

  private static Compilation forms(String body) {
    return Compilation.builder()
        .java(
            "com.example.Owner",
            "package com.example; public record Owner(String firstName, boolean active) {}")
        .template("form.html", FORM_HEAD + " method=\"post\">" + body + "</form>")
        .compile()
        .assertSuccess();
  }

  private static String renderForm(Compilation c, Boolean off, StubForms f) {
    return c.render("form", model("owner", c.newInstance("com.example.Owner", "Ann", true), "off", off), ctx(f));
  }

  // ---- the generated markup is recorded as written ----

  @Test
  void csrfHiddenInputIsAnOptionalStaticWithTwoAttributeHoles() {
    Compilation c = forms("x");
    String skeleton = c.skeleton("form").body().toString();
    // Both holes carry the <form> start tag's position, so a verifier error about them names the form.
    assertTrue(
        skeleton.contains(
            "opt:CSRF_TOKEN(\"<input type=\\\"hidden\\\"\" \" name=\\\"\" ${ATTR name @form.html:1:1} \"\\\"\""
                + " \" value=\\\"\" ${ATTR value @form.html:1:1} \"\\\"\" \" />\")"),
        skeleton);
    StubForms f = new StubForms();
    assertEquals("<form method=\"post\">x</form>", renderForm(c, null, f));
    f.token = new Forms.CsrfToken("_c\"", "t<\"&");
    assertEquals(
        "<form method=\"post\"><input type=\"hidden\" name=\"_c&quot;\" value=\"t&lt;&quot;&amp;\" />x</form>",
        renderForm(c, null, f));
  }

  @Test
  void checkboxMarkerIsRecordedAndOptionalWhenDisabledIsComputed() {
    Compilation c =
        forms("<input type=\"checkbox\" t:field=\"*{active}\"><input type=\"checkbox\" t:field=\"*{active}\" t:disabled=\"off\">");
    String skeleton = c.skeleton("form").body().toString();
    assertTrue(skeleton.contains("\"<input type=\\\"hidden\\\" name=\\\"_\" ${ATTR name @form.html:"), skeleton);
    assertTrue(skeleton.contains("opt:CHECKBOX_MARKER(\"<input type=\\\"hidden\\\" name=\\\"_\""), skeleton);
    String on = renderForm(c, true, new StubForms());
    assertEquals(1, on.split("name=\"_active\"", -1).length - 1, on);
    String off = renderForm(c, null, new StubForms());
    assertEquals(2, off.split("name=\"_active\"", -1).length - 1, off);
  }

  @Test
  void errorsAreARepetitionSeparatedByBr() {
    Compilation c = forms("<p t:errors=\"*{firstName}\">e</p>");
    String skeleton = c.skeleton("form").body().toString();
    assertTrue(skeleton.contains("opt:ERRORS(\"<p\" \">\" rep:ERRORS(${TEXT @form.html:1:"), skeleton);
    assertTrue(skeleton.contains(" / \"<br />\") \"</p>\")"), skeleton);
    StubForms f = new StubForms();
    assertEquals("<form method=\"post\"></form>", renderForm(c, null, f));
    f.errors.add("a<b");
    f.errors.add("c");
    f.errors.add("d");
    assertEquals("<form method=\"post\"><p>a&lt;b<br />c<br />d</p></form>", renderForm(c, null, f));
  }

  @Test
  void errorsOnATemplateAreRejectedBecauseTheirSeparatorSwitchesItsMode() {
    // In a <template>, the first <br /> leaves template mode: only a second message would (#707).
    Compilation.builder()
        .java("com.example.Owner", "package com.example; public record Owner(String firstName, boolean active) {}")
        .template("form.html", FORM_HEAD + " method=\"post\"><template t:errors=\"*{firstName}\">e</template></form>")
        .compile()
        .assertError("the separator of the messages of this t:errors", "UNBALANCED");
  }

  @Test
  void classappendMergesIntoOneOptionalAttribute() {
    Compilation c =
        Compilation.builder()
            .template("p.html", "<p " + NS + " t:params=\"String s\" class=\"a\" t:classappend=\"s\">x</p>")
            .compile()
            .assertSuccess();
    String skeleton = c.skeleton("p").body().toString();
    assertTrue(skeleton.startsWith("\"<p\" opt:ATTRIBUTE(\" class=\\\"\" ${ATTR class @p.html:1:"), skeleton);
    assertEquals("<p class=\"a b&quot;\">x</p>", c.render("p", model("s", "b\"")));
    assertEquals("<p class=\"a\">x</p>", c.render("p", model("s", null)));
  }

  @Test
  void eachRepeatsTheWhitespaceBeforeTheElement() {
    Compilation c =
        Compilation.builder()
            .template(
                "l.html",
                "<ul " + NS + " t:import=\"java.util.List\" t:params=\"List<String> xs\">\n  <li t:each=\"x : xs\" t:text=\"x\">i</li>\n</ul>")
            .compile()
            .assertSuccess();
    assertTrue(c.skeleton("l").body().toString().contains("rep:EACH(\"<li\" \">\" ${TEXT @l.html:2:"), c.skeleton("l").toString());
    assertTrue(c.skeleton("l").body().toString().contains("\"</li>\" / \"\\n  \")"), c.skeleton("l").toString());
    assertEquals("<ul>\n  </ul>", c.render("l", model("xs", List.of())).replace("\n</ul>", "</ul>"));
    assertEquals("<ul>\n  <li>a</li>\n  <li>b</li>\n</ul>", c.render("l", model("xs", List.of("a", "b"))));
  }

  @Test
  void nullOmittedAttributesAndConditions() {
    Compilation c =
        Compilation.builder()
            .template(
                "a.html",
                "<div " + NS + " t:params=\"String s, boolean b\"><a t:href=\"s\" t:title=\"s\" t:if=\"b\">x</a>"
                    + "<i t:unless=\"b\">n</i><b t:switch=\"s\"><u t:case=\"'x'\">1</u><u t:case=\"*\">2</u></b></div>")
            .compile()
            .assertSuccess();
    String skeleton = c.skeleton("a").body().toString();
    assertTrue(skeleton.contains("opt:CONDITION(\"<a\" opt:ATTRIBUTE(\" href=\\\"\" ${URL href"), skeleton);
    assertTrue(skeleton.contains("opt:CASE(\"<u\""), skeleton);
    assertEquals("<div><a href=\"x\" title=\"x\">x</a><b><u>1</u></b></div>", c.render("a", model("s", "x", "b", true)));
    assertEquals("<div><i>n</i><b><u>2</u></b></div>", c.render("a", model("s", null, "b", false)));
  }

  @Test
  void everyRemoveMode() {
    Compilation c =
        Compilation.builder()
            .template(
                "r.html",
                "<div " + NS + " t:params=\"String s\"><p t:remove=\"all\">a[[${s}]]</p><p t:remove=\"body\">b[[${s}]]</p>"
                    + "<p t:remove=\"tag\">c[[${s}]]</p><p t:remove=\"all-but-first\"> <i>[[${s}]]</i><i>2</i> </p>"
                    + "<p t:remove=\"none\">e[[${s}]]</p></div>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<div><p></p>cv<p> <i>v</i> </p><p>ev</p></div>", c.render("r", model("s", "v")));
  }

  @Test
  void fragmentsAreCallsWithBoundArguments() {
    Compilation c =
        Compilation.builder()
            .template(
                "f.html",
                "<div " + NS + " t:import=\"dev.tessera.runtime.Fragment\" t:params=\"String s\">"
                    + "<section t:fragment=\"card(Fragment content)\">[<i t:replace=\"content\">c</i>]</section>"
                    + "<section t:fragment=\"outer(Fragment inner)\">(<b t:replace=\"~{:: card(${inner})}\">o</b>)</section>"
                    + "<em t:fragment=\"leaf\">leaf</em>"
                    + "<div t:insert=\"~{:: outer(~{:: leaf})}\">p</div>"
                    + "<div t:insert=\"~{:: card(~{::span})}\">q</div><span t:text=\"s\">s</span></div>")
            .compile()
            .assertSuccess();
    Skeleton.TemplateSkeleton t = c.skeleton("f");
    assertEquals("\"<section\" \">\" \"[\" $param{content} \"]\" \"</section>\"", strip(t.fragments().get("card")));
    assertTrue(t.fragments().get("outer").toString().contains("call{f :: card {content=$param{inner}}}"), t.toString());
    assertTrue(t.body().toString().contains("call{f :: outer {inner=call{f :: leaf}}}"), t.toString());
    assertEquals(
        "<div><em>leaf</em><div><section>(<section>[<em>leaf</em>]</section>)</section></div>"
            + "<div><section>[<span>v</span>]</section></div><span>v</span></div>",
        c.render("f", model("s", "v")));
  }

  @Test
  void rawInsertsAndScriptJson() {
    Compilation c =
        Compilation.builder()
            .java("com.example.Item", "package com.example; public record Item(String name, int n) {}")
            .template(
                "j.html",
                "<div " + NS + " t:import=\"com.example.Item, dev.tessera.runtime.SafeHtml\""
                    + " t:params=\"SafeHtml h, Item item, String s\"><div t:utext=\"h\">u</div>[(${h})]"
                    + "<script>var a = [[${item}]]; var b = [[${s}]];</script></div>")
            .compile()
            .assertSuccess();
    String skeleton = c.skeleton("j").body().toString();
    assertTrue(skeleton.contains("$raw{SAFE_HTML"), skeleton);
    assertTrue(skeleton.contains("\"var a = \" ${JAVASCRIPT @j.html:1:"), skeleton);
    String out =
        c.render(
            "j",
            model(
                "h", TestFragments.safeHtml("<b>B</b>"),
                "item", c.newInstance("com.example.Item", "</script>", 3),
                "s", "x'y"));
    assertEquals(
        "<div><div><b>B</b></div><b>B</b><script>var a = ({\"name\":\"\\u003c\\/script\\u003e\",\"n\":3});"
            + " var b = (\"x\\u0027y\");</script></div>",
        out);
  }

  // ---- the membership check rejects a skeleton that differs from the output ----

  private static Compilation page() {
    return Compilation.builder()
        .template(
            "page.html",
            "<div " + NS + " t:params=\"String s, boolean b\"><b t:if=\"b\">yes</b><p t:text=\"s\">x</p></div>")
        .compile()
        .assertSuccess();
  }

  private static SkeletonMatcher.Result match(Skeleton.Seq body, String output) {
    return SkeletonMatcher.match(body, output, id -> null);
  }

  @Test
  void theRealSkeletonMatches() {
    Compilation c = page();
    String out = c.render("page", model("s", "a&b", "b", false));
    assertTrue(match(c.skeleton("page").body(), out).matches());
  }

  @Test
  void aSkeletonMissingOneStaticByteFails() {
    Compilation c = page();
    String out = c.render("page", model("s", "a", "b", true));
    Skeleton.Seq body =
        map(c.skeleton("page").body(), n -> n instanceof Skeleton.Static && ((Skeleton.Static) n).text().equals("</p>")
            ? new Skeleton.Static("</p", n.pos())
            : n);
    SkeletonMatcher.Result r = match(body, out);
    assertFalse(r.matches());
    assertEquals(out.indexOf("</p>") + 3, r.offset(), r.expected());
    assertThrows(AssertionError.class, () -> assertMatches(body, out));
  }

  @Test
  void aHoleRecordedWithTheWrongEscaperFails() {
    Compilation c = page();
    String out = c.render("page", model("s", "a&b", "b", false));
    Skeleton.Seq body =
        map(c.skeleton("page").body(), n -> n instanceof Skeleton.Hole
            ? new Skeleton.Hole(Skeleton.Escaper.JSON, null, n.pos())
            : n);
    SkeletonMatcher.Result r = match(body, out);
    assertFalse(r.matches());
    assertEquals(out.indexOf("&amp;"), r.offset(), r.expected());
    assertTrue(r.expected().contains("hole ${JSON"), r.expected());
  }

  @Test
  void aMissingOptFails() {
    Compilation c = page();
    String out = c.render("page", model("s", "a", "b", false));
    Skeleton.Seq body =
        map(c.skeleton("page").body(), n -> n instanceof Skeleton.Opt ? ((Skeleton.Opt) n).body() : n);
    SkeletonMatcher.Result r = match(body, out);
    assertFalse(r.matches());
    // "<div>" then "<b" expected: the '<' of "<p>" matches, the 'p' does not.
    assertEquals("<div><".length(), r.offset(), r.expected());
  }

  @Test
  void escaperAlphabets() {
    assertTrue(ends(Skeleton.Escaper.TEXT, "a&amp;{").get(7));
    assertFalse(ends(Skeleton.Escaper.TEXT, "<!---->").get(7)); // #679: no escaper writes a comment
    assertFalse(ends(Skeleton.Escaper.ATTR, "<!---->").get(7));
    assertEquals(1, ends(Skeleton.Escaper.TEXT, "a&b").length() - 1);
    assertEquals(0, ends(Skeleton.Escaper.ATTR, "\"").length() - 1);
    assertEquals(0, ends(Skeleton.Escaper.TEXT, "\0").length() - 1);
    assertEquals(0, ends(Skeleton.Escaper.TEXT, "\ud800x").length() - 1);
    assertEquals(2, ends(Skeleton.Escaper.TEXT, "😀").length() - 1);
    assertEquals(10, ends(Skeleton.Escaper.JSON, "{\"a\":\"\\/\"}<").length() - 1);
    assertEquals(0, ends(Skeleton.Escaper.JSON, "'").length() - 1);
    assertEquals(6, ends(Skeleton.Escaper.JAVASCRIPT, "(-1.5)<").length() - 1);
    assertEquals(0, ends(Skeleton.Escaper.JAVASCRIPT, "'").length() - 1);
    assertEquals(0, ends(Skeleton.Escaper.JSON, " ").length() - 1);
  }

  @Test
  void repetitionOptionAndAlternative() {
    Skeleton.Seq body =
        seq(
            new Skeleton.Rep(Skeleton.RepKind.EACH, seq(st("a")), seq(st(",")), null),
            new Skeleton.Alt(List.of(seq(st("x")), seq(st("yy"))), null),
            new Skeleton.Opt(Skeleton.OptKind.CONDITION, seq(st("!")), null));
    for (String ok : List.of("x", "a,a,ayy!", "ayy", "ax!")) {
      assertTrue(match(body, ok).matches(), ok);
    }
    for (String bad : List.of("", "a,x", ",ax", "aax", "xyy", "x!!")) {
      assertFalse(match(body, bad).matches(), bad);
    }
  }

  @Test
  void callsBindFragmentArgumentsInTheCallersBindings() {
    Skeleton.FragmentId wrap = new Skeleton.FragmentId("t", "wrap");
    Skeleton.FragmentId pass = new Skeleton.FragmentId("t", "pass");
    Map<Skeleton.FragmentId, Skeleton.Seq> fragments = new LinkedHashMap<>();
    fragments.put(wrap, seq(st("["), new Skeleton.ParamInsert("c", null), st("]")));
    fragments.put(pass, seq(new Skeleton.Call(wrap, Map.of("c", seq(new Skeleton.ParamInsert("d", null))), null)));
    Skeleton.Seq body = seq(new Skeleton.Call(pass, Map.of("d", seq(st("x"))), null));
    Function<Skeleton.FragmentId, Skeleton.Seq> f = fragments::get;
    assertTrue(SkeletonMatcher.match(body, "[x]", f).matches());
    assertTrue(SkeletonMatcher.match(body, "[]", f).matches()); // a null Fragment
    assertFalse(SkeletonMatcher.match(body, "[y]", f).matches());
    // Unbound: anything.
    assertTrue(SkeletonMatcher.match(seq(new Skeleton.Call(wrap, Map.of(), null)), "[<y>]", f).matches());
  }

  // ---- helpers ----

  private static BitSet ends(Skeleton.Escaper e, String s) {
    return SkeletonMatcher.holeEnds(e, s, 0);
  }

  private static Skeleton.Static st(String s) {
    return new Skeleton.Static(s, null);
  }

  private static Skeleton.Seq seq(Skeleton.Node... nodes) {
    return new Skeleton.Seq(List.of(nodes));
  }

  private static void assertMatches(Skeleton.Seq body, String out) {
    if (!match(body, out).matches()) {
      throw new AssertionError("not in skeleton");
    }
  }

  /** Skeleton.Seq toString without positions. */
  private static String strip(Skeleton.Seq s) {
    return s.toString().replaceAll(" @[^ }]*", "");
  }

  /** {@code seq} with every node replaced by {@code f} (applied bottom-up, Opt bodies included). */
  private static Skeleton.Seq map(Skeleton.Seq seq, Function<Skeleton.Node, Skeleton.Node> f) {
    List<Skeleton.Node> out = new ArrayList<>();
    for (Skeleton.Node n : seq.nodes()) {
      Skeleton.Node m = n;
      if (n instanceof Skeleton.Opt) {
        Skeleton.Opt o = (Skeleton.Opt) n;
        m = new Skeleton.Opt(o.kind(), map(o.body(), f), o.pos());
      } else if (n instanceof Skeleton.Rep) {
        Skeleton.Rep r = (Skeleton.Rep) n;
        m = new Skeleton.Rep(r.kind(), map(r.body(), f), map(r.separator(), f), r.pos());
      }
      Skeleton.Node replaced = f.apply(m);
      if (replaced instanceof Skeleton.Seq) {
        out.addAll(((Skeleton.Seq) replaced).nodes());
      } else {
        out.add(replaced);
      }
    }
    return new Skeleton.Seq(out);
  }
}
