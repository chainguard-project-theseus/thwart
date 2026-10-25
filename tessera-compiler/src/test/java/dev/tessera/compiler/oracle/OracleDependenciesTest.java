package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Smoke test for the oracle's test-scope parsers (#317): each loads and parses. The real
 * {@link SpecParser} implementation is #318's; the script check is #319's.
 */
class OracleDependenciesTest {

  /** Current spec keeps {@code <title>} as RCDATA inside {@code <select>}: the script is inert text. */
  private static final String SELECT_TITLE_SCRIPT =
      "<!DOCTYPE html><select><title><script>x()</script></title></select>";

  @Test
  void validator26ParsesInItsOwnClassLoader() throws Exception {
    String jar = System.getProperty("tessera.oracle.vnu");
    assertNotNull(jar, "tessera.oracle.vnu is set by the tessera-compiler pom");
    Path path = Path.of(jar);
    assertEquals(true, Files.isRegularFile(path), jar);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {path.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> policyType = loader.loadClass("nu.validator.htmlparser.common.XmlViolationPolicy");
      Class<?> builderType = loader.loadClass("nu.validator.htmlparser.dom.HtmlDocumentBuilder");
      Object allow = policyType.getField("ALLOW").get(null);
      Object builder = builderType.getConstructor(policyType).newInstance(allow);
      Method parse = builderType.getMethod("parse", InputSource.class);
      Document doc = (Document) parse.invoke(builder, new InputSource(new StringReader(SELECT_TITLE_SCRIPT)));
      assertEquals(0, doc.getElementsByTagName("script").getLength());
      assertEquals("<script>x()</script>", doc.getElementsByTagName("title").item(0).getTextContent());
    }
    // The validator jar stays off the test classpath (SpecParsers loads it as above).
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
