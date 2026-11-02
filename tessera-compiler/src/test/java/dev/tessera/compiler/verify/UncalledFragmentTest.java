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
        + "<option t:fragment=\"o(String s)\" t:text=\"s\">x</option>"
        + "<t:block t:fragment=\"t(String s)\">[[${s}]]</t:block>").assertSuccess();
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
