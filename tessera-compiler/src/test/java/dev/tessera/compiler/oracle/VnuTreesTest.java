package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The engine-drift normal form's Java half (#399): what {@link VnuTrees} writes, and that the trees
 * in the Node job's fixture are what the vnu engine builds (so the job's self-check compares the
 * browsers with real vnu trees, and a change to either half's normal form fails one of the two).
 */
class VnuTreesTest {

  private static String tree(String engine, String html, boolean scripting) {
    return VnuTrees.tree(SpecParsers.named(engine), html, scripting);
  }

  private static String body(String html) {
    String t = tree(SpecParsers.VNU_26, "<body>" + html, true);
    String prefix = "[[\"html\",\"html\",[],[[\"head\",\"html\",[],[]],[\"body\",\"html\",[],";
    assertTrue(t.startsWith(prefix), t);
    return t.substring(prefix.length(), t.length() - "]]]]".length());
  }

  @Test
  void theNormalForm() {
    // Attributes sorted, text kept exactly (whitespace-only too), comments dropped and the text
    // around them merged.
    assertEquals("[[\"p\",\"html\",[[\"a\",\"1\"],[\"b\",\"2\"]],[\"x y\"]],\" \",[\"i\",\"html\",[],[]]]",
        body("<p b=2 a=1>x<!--c--> y</p> <i></i>"));
    // Namespaces, adjusted names, xlink attributes; xmlns attributes are dropped.
    assertEquals("[[\"svg\",\"svg\",[[\"viewBox\",\"0 0 1 1\"]],[[\"a\",\"svg\",[[\"xlink:href\",\"#x\"]],"
            + "[[\"foreignObject\",\"svg\",[],[]]]]]],[\"math\",\"math\",[],[[\"mi\",\"math\",[],[\"m\"]]]]]",
        body("<svg viewbox=\"0 0 1 1\" xmlns=\"http://www.w3.org/2000/svg\"><a xlink:href=\"#x\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\"><foreignobject></foreignobject></a></svg>"
            + "<math><mi>m</mi></math>"));
    // Template contents are one #content child.
    assertEquals("[[\"template\",\"html\",[],[[\"#content\",\"\",[],[[\"p\",\"html\",[],[\"t\"]]]]]]]",
        body("<template><p>t</p></template>"));
    // vnu's U+hex spelling of characters that can't be in an XML name is undone.
    assertEquals("[[\"p\",\"html\",[[\"<c\",\"\"],[\"a\\\"b\",\"1\"]],[]]]", body("<p a\"b=1 <c></p>"));
    // An <iframe srcdoc> gets its document, unless it is sandboxed.
    assertTrue(body("<iframe srcdoc=\"<b>s</b>\"></iframe>").contains("[\"#srcdoc\",\"\",[],[[\"html\""));
    assertTrue(!body("<iframe sandbox srcdoc=\"<b>s</b>\"></iframe>").contains("#srcdoc"));
  }

  @Test
  void scriptingIsKeptApart() {
    String html = "<body><noscript><b>n</b></noscript>";
    assertTrue(tree(SpecParsers.VNU_26, html, true).contains("[\"noscript\",\"html\",[],[\"<b>n</b>\"]]"));
    assertTrue(tree(SpecParsers.VNU_26, html, false).contains("[\"noscript\",\"html\",[],[[\"b\",\"html\",[],[\"n\"]]]]"));
    String json = VnuTrees.json(html);
    assertEquals("{\"scripting-on\":" + tree(SpecParsers.VNU_26, html, true) + ",\"scripting-off\":"
        + tree(SpecParsers.VNU_26, html, false) + "}", json);
  }

  private static final Pattern RECORD = Pattern.compile(
      "^\\{\"id\":\"([0-9a-f]{12})\",\"name\":\"([^\"]*)\",.*\"render\":(\"(?:[^\"\\\\]|\\\\.)*\"),\"verdict\":\"[A-Z]+\",\"trees\":(.*)\\}$");

  @Test
  void theFixtureTreesAreWhatVnuBuilds() throws IOException {
    Path fixture = Path.of("src/test/node/fixture.jsonl");
    int checked = 0;
    for (String line : Files.readAllLines(fixture, StandardCharsets.UTF_8)) {
      Matcher m = RECORD.matcher(line);
      if (!m.matches()) {
        continue; // no trees: not compared
      }
      String render = unquote(m.group(3));
      if (m.group(2).equals("S10-drift-injected")) {
        // The injected difference: its trees are another render's.
        assertNotEquals(VnuTrees.json(render), m.group(4));
        continue;
      }
      assertEquals(VnuTrees.json(render), m.group(4), m.group(2) + ": regenerate the trees of fixture.jsonl from VnuTrees");
      checked++;
    }
    assertEquals(2, checked);
  }

  /** A JSON string literal (as ExportRecord writes it) as a Java string. */
  private static String unquote(String json) {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < json.length() - 1; i++) {
      char c = json.charAt(i);
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char e = json.charAt(++i);
      switch (e) {
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> sb.append(e);
      }
    }
    return sb.toString();
  }
}
