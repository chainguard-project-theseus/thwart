package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;

/**
 * A fragment no template calls is checked on its own (#461). Before, the verifier saw a fragment
 * only in the states its calls write it in, so an uncalled one's holes had no parser state at all,
 * though its public fragment method can still render it.
 */
class UncalledFragmentTest {
  private static final String HEAD =
      "<div xmlns:t=\"https://tessera.dev\" t:import=\"dev.tessera.runtime.SafeHtml\">\n";

  private static Compilation compile(String body) {
    return Compilation.builder().template("f.html", HEAD + body + "\n</div>").compile();
  }

  @Test
  void anUncalledFragmentsHolesAreChecked() {
    // A SafeHtml in a <p>: its markup could close the <p> (RAW_CLOSES_ELEMENT), uncalled or not.
    compile("<p t:fragment=\"g(SafeHtml h)\" t:utext=\"h\"></p>")
        .assertError("f.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
    // Called, the same fragment fails at the call site.
    compile("<p t:fragment=\"g(SafeHtml h)\" t:utext=\"h\"></p><div t:replace=\"~{:: g(${null})}\"></div>")
        .assertError("f.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
  }

  @Test
  void anUncalledFragmentIsCheckedInItsImpliedContext() {
    // An <option> root implies a <select>: a <b> in it is exclusion (c).
    compile("<option t:fragment=\"o\"><b>x</b></option>").assertError("f.html:2:", "[verifier:SELECT_CONTENT]");
    // Rows, cells, options, list items and text-first fragments fit their implied context.
    compile("<table><tr t:fragment=\"r(String s)\"><td>[[${s}]]</td></tr></table>"
        + "<ul><li t:fragment=\"l(String s)\">[[${s}]]</li></ul>"
        + "<select><option t:fragment=\"o(String s)\" t:text=\"s\">x</option></select>"
        + "<t:block t:fragment=\"t(String s)\">[[${s}]]</t:block>").assertSuccess();
  }

  @Test
  void anUncalledFragmentIsCheckedInTheContextItIsDeclaredIn() {
    // Declared in <svg>, its <title> is SVG: text, not the RCDATA of an HTML <title>.
    compile("<svg><title t:fragment=\"f(String s)\">[[${s}]]</title><g t:fragment=\"g\"><path d=\"M0 0\"/></g></svg>"
        + "<math><mrow t:fragment=\"m(String s)\"><mi>[[${s}]]</mi></mrow></math>").assertSuccess();
  }

  @Test
  void anUncalledFragmentDeclaredWhereNoFragmentFitsIsRejected() {
    Compilation c = compile("<noscript><p t:fragment=\"g(String v)\" t:text=\"v\">x</p></noscript>");
    c.assertError("f.html:2:11: error: no template inserts fragment f :: g, and it is declared where no fragment fits",
        "declare it in HTML, <svg> or <math> content, or delete it [verifier:UNCALLED_FRAGMENT_FITS_NOWHERE]");
    assertEquals(1, c.errors().size(), c.errors().toString());
  }

  @Test
  void aFragmentOnlyAnUncalledOneCallsIsCheckedThroughThatCall() {
    // h is reached from g, which is checked in <body>: one error, at the call, not a second one for h.
    Compilation c = compile("<t:block t:fragment=\"g(SafeHtml x)\"><p t:replace=\"~{:: h(${x})}\"></p></t:block>"
        + "<p t:fragment=\"h(SafeHtml x)\"><b t:utext=\"x\"></b></p>");
    c.assertError("f.html:2:", "in fragment f :: h", "[verifier:RAW_CLOSES_ELEMENT]");
    assertEquals(1, c.errors().size(), c.errors().toString());
  }
}
