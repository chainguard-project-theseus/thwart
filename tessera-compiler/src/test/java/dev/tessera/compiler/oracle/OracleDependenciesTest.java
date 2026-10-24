package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.dom.HtmlDocumentBuilder;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstRoot;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Smoke test for the oracle's test-scope parsers (#317): each loads and parses. The real
 * {@link SpecParser} implementations are #318's; the script check is #319's.
 */
class OracleDependenciesTest {

  /** Classic and current spec disagree here: 1.4.16 makes the script live, 26.10.12 doesn't. */
  private static final String SELECT_TITLE_SCRIPT =
      "<!DOCTYPE html><select><title><script>x()</script></title></select>";

  @Test
  void htmlparser14Parses() throws Exception {
    HtmlDocumentBuilder builder = new HtmlDocumentBuilder(XmlViolationPolicy.ALLOW);
    Document doc = builder.parse(new InputSource(new StringReader(SELECT_TITLE_SCRIPT)));
    assertEquals("select", doc.getElementsByTagName("script").item(0).getParentNode().getLocalName());
  }

  @Test
  void validator26ParsesInItsOwnClassLoader() throws Exception {
    String jar = System.getProperty("tessera.oracle.vnu");
    assertNotNull(jar, "tessera.oracle.vnu is set by the tessera-compiler pom");
    Path path = Path.of(jar);
    assertEquals(true, Files.isRegularFile(path), jar);
    // Parent is the platform loader, so htmlparser 1.4.16 on the test classpath can't shadow it.
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {path.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> policyType = loader.loadClass("nu.validator.htmlparser.common.XmlViolationPolicy");
      Class<?> builderType = loader.loadClass("nu.validator.htmlparser.dom.HtmlDocumentBuilder");
      assertNotEquals(HtmlDocumentBuilder.class, builderType);
      Object allow = policyType.getField("ALLOW").get(null);
      Object builder = builderType.getConstructor(policyType).newInstance(allow);
      Method parse = builderType.getMethod("parse", InputSource.class);
      Document doc = (Document) parse.invoke(builder, new InputSource(new StringReader(SELECT_TITLE_SCRIPT)));
      // Current spec keeps <title> as RCDATA inside <select>: the script is inert text.
      assertEquals(0, doc.getElementsByTagName("script").getLength());
      assertEquals("<script>x()</script>", doc.getElementsByTagName("title").item(0).getTextContent());
    }
  }

  @Test
  void rhinoParses() {
    CompilerEnvirons env = new CompilerEnvirons();
    env.setLanguageVersion(Context.VERSION_ECMASCRIPT);
    AstRoot root = new Parser(env).parse("var a = {\"k\": [1, true, null]};", "slot.js", 1);
    assertEquals(1, root.getStatements().size());
  }
}
