package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.w3c.dom.Document;

/** Smoke test for the oracle's test-scope parsers (#317): each loads and parses. */
class OracleDependenciesTest {

  @Test
  void validator26ParsesInItsOwnClassLoader() {
    // Current spec keeps <title> as RCDATA inside <select>: the script is inert text.
    Document doc = SpecParser.parse("<!DOCTYPE html><select><title><script>x()</script></title></select>", false);
    assertEquals(0, doc.getElementsByTagName("script").getLength());
    assertEquals("<script>x()</script>", doc.getElementsByTagName("title").item(0).getTextContent());
    // The validator jar stays off the test classpath.
    assertThrows(ClassNotFoundException.class, () -> Class.forName("nu.validator.htmlparser.dom.HtmlDocumentBuilder"));
  }

  @Test
  void rhinoParses() {
    CompilerEnvirons env = new CompilerEnvirons();
    env.setLanguageVersion(Context.VERSION_ECMASCRIPT);
    AstRoot root = new Parser(env).parse("var a = {\"k\": [1, true, null]};", "slot.js", 1);
    assertEquals(1, root.getStatements().size());
  }
}
