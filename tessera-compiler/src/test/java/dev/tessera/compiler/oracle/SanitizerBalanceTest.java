package dev.tessera.compiler.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.runtime.HtmlPolicy;
import dev.tessera.runtime.SafeHtml;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * The sanitizer's output is balanced {@code FLOW} content under every policy (#262, #329): parsed
 * by both spec parsers, with scripting on and off, at insertion points that meet {@code FLOW}
 * (body, {@code div}, {@code td}, {@code li}, {@code template}), it yields exactly the tree its
 * tags spell. So the parser implied no end tag, moved nothing (foster parenting, formatting
 * reconstruction, adoption agency), switched no namespace, and leaves the insertion point in the
 * state it found it: the element written after the output is still the insertion point's next
 * sibling.
 */
class SanitizerBalanceTest {

  private static final Map<String, HtmlPolicy> POLICIES = new LinkedHashMap<>();

  static {
    POLICIES.put("BASIC", HtmlPolicy.BASIC);
    POLICIES.put("RICH", HtmlPolicy.RICH);
    // RICH widened: any class and remote images
    POLICIES.put(
        "wide",
        HtmlPolicy.builder()
            .allowElements(HtmlPolicy.ELEMENTS.toArray(new String[0]))
            .allowAttributes(HtmlPolicy.ATTRIBUTES.toArray(new String[0]))
            .allowClasses("*")
            .allowRemoteImages()
            .allowLinkTarget()
            .build());
    // Table parts and li without their containers, and a without phrasing parents, must still balance.
    POLICIES.put(
        "partial",
        HtmlPolicy.builder()
            .allowElements("td", "tr", "li", "a", "p", "pre", "img", "b", "caption", "thead")
            .allowAttributes("id", "colspan")
            .allowClasses("x-*")
            .linkRel()
            .allowDataImages()
            .build());
  }

  /** Insertion points meeting FLOW: prefix and suffix around the output, and the id of its parent. */
  private static final String[][] CONTEXTS = {
    {"<hr id=\"tb\">", "<hr id=\"ta\">"},
    {"<div id=\"tp\">", "</div><hr id=\"ta\">"},
    {"<table><tbody><tr><td id=\"tp\">", "</td><td id=\"ta\"></td></tr></tbody></table>"},
    {"<ul><li id=\"tp\">", "</li><li id=\"ta\"></li></ul>"},
    {"<template id=\"tp\">", "</template><hr id=\"ta\">"},
  };

  /** Hostile inputs: mXSS and parser-confusion vectors, misnesting the tree builder would repair. */
  static final String[] HOSTILE = {
    // nested forms and other elements with special parsing
    "<form><form>x</form></form>y",
    "<form id=a><div><form></div>x</form>",
    "<button><button>x</button>",
    "<nobr><nobr>x</nobr>",
    "<dd>a<dt>b<dd>c",
    "<select><option><table><tr><td>x</td></tr></table></option></select>after",
    "<frameset><frame src=x></frameset>",
    "<image src=x onerror=alert(1)>",
    "<listing>\nx</listing>",
    "<textarea>\n<p>x</p></textarea>",
    // namespace confusion
    "<svg><p><style><img src=x onerror=alert(1)>",
    "<svg></p><style><a id=\"</style><img src=1 onerror=alert(1)>\">",
    "<math><mi><mglyph><svg><mtext><textarea><path id=\"</textarea><img onerror=alert(1) src=1>\">",
    "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
    "<math><annotation-xml encoding=\"text/html\"><p>x</p></annotation-xml></math>",
    "<svg><foreignObject><p>x</p></foreignObject></svg>",
    "<table><tr><td><svg><desc><table></table></desc></svg></td></tr></table>",
    "<svg><![CDATA[</svg><img src=x onerror=alert(1)>]]>",
    // raw text and RCDATA tricks
    "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">",
    "<noscript><style></noscript><img src=x onerror=alert(1)>",
    "<style><img src=x onerror=alert(1)>",
    "<title><p>x</title><img src=y>",
    "<xmp><p>x</xmp><b>y</b>",
    "<iframe><p>x</iframe>",
    "<plaintext><p>x",
    "<script><!--<script></script>alert(1)</script>-->",
    // comments and CDATA
    "<!--<img src=x>-->a",
    "<!--><img src=x onerror=alert(1)>-->",
    "<!--!><p>x</p>",
    "<!-- a --!><p>x</p>",
    "<![CDATA[<img src=x onerror=alert(1)>]]>",
    "<?xml x?><p>x</p>",
    "<!DOCTYPE html><p>x</p>",
    // implied end tags and misnesting
    "<p>a<p>b<div>c</div>d</p>",
    "<p><table><tr><td>x</td></tr></table></p>",
    "<p><ul><li>x</li></ul></p>",
    "<p><h1>x</h1></p>",
    "<p><pre>x</pre></p>",
    "<p><blockquote>x</blockquote></p>",
    "<p>x<hr>y</p>",
    "<h1>a<h2>b</h2>c</h1>",
    "<h1><b><h2>x</h2></b></h1>",
    "<a href=/1>a<a href=/2>b</a>c</a>",
    "<a href=/1><b>a<a href=/2>b</a></b></a>",
    "<a href=/x><div>block</div></a>",
    "<a href=/1><table><tr><td><a href=/2>x</a></td></tr></table></a>",
    "<b><i><u><s>x</b>y</i>z",
    "<b><p>x</b>y</p>",
    "<b><div>x</div></b>",
    "<span><li>x</li></span>",
    "<ul><li>a<li>b<ul><li>c</ul></ul>",
    "<ul>text<li>x</li>more</ul>",
    "<li>orphan</li>",
    "<div><li>x</li></div>",
    "<ol><p>x</p><li>y</li></ol>",
    "<li><p>a<li>b",
    "<ul><li><div><li>x</div></ul>",
    "<pre>\nx</pre>",
    "<pre>\n\nx</pre>",
    "<pre>&#10;x</pre>",
    "<pre>\r\nx</pre>",
    "<pre><b>\nx</b></pre>",
    "<pre>{{x}}</pre>",
    "a\r\nb\rc",
    "<p>{{a}}</p>",
    "<br/></br></p><hr/>",
    // tables
    "<table><p>x</p></table>",
    "<table></p><tr><td>a</td></p></tr></table>",
    "<table><td>x<table>y</table>",
    "<table>text<tr>more<td>cell</td>tail</tr></table>",
    "<table><tr><td>a<td>b<tr><td>c</table>",
    "<table><caption>c<tr><td>x</table>",
    "<table><thead><td>h</td><tbody><tr><td>b</td></tbody><tfoot><tr><th>f</table>",
    "<table><table><tr><td>x</td></tr></table></table>",
    "<table><tr><td><ul><li><td>x</ul></table>",
    "<table><tr><td><p>a<td>b</p></td></tr></table>",
    "<table><b>x</b><tr><td>y</td></tr></table>",
    "<table><a href=/x>x</a><tr><td>y</td></tr></table>",
    "<table><tr><br><img src=/i><hr><td>y</td></tr></table>",
    "<td>no table</td>",
    "<tr><td>x</td></tr>",
    "<caption>x</caption>",
    "<thead><tr><td>x</td></tr></thead>",
    "<table><tr><td colspan=2 rowspan=\"3\" align=center>x</td></tr></table>",
    "<table><tbody><tr><td></div></td></tr></tbody></table><div>after</div>",
    "<div><table><tr><td></div>x</td></tr></table>",
    // attributes
    "<p id=location class=\"ng-init: x a b\" style=\"color:red\" onclick=alert(1)>x</p>",
    "<a href=\"#top\" id=\"top\" target=_blank title=\"t {{x}}\">x</a>",
    "<img src=javascript:alert(1)><img src=/ok.png alt=\"a&eacute;\" width=10 height=x>",
    "<img src=\"data:image/png;base64,iVBORw0KGgo=\"><img src=\"data:image/svg+xml,<svg onload=alert(1)>\">",
    "<ol start=3 reversed><li value=-2>x</li></ol>",
    "<p title='a\"b' class=x-y>x</p>",
    // deep and wide
    "<b>".repeat(150) + "x",
    "<table>".repeat(60) + "x",
    "<div><table><tr><td>".repeat(40) + "x",
    "<ul><li>".repeat(60) + "x",
    "<p><a href=/x><b>".repeat(40) + "<div>y",
  };

  @Test
  void hostileInputsGiveBalancedOutput() {
    for (String input : HOSTILE) {
      for (Map.Entry<String, HtmlPolicy> p : POLICIES.entrySet()) {
        assertBalanced(p.getKey(), input, SafeHtml.sanitize(input, p.getValue()).html());
      }
    }
  }

  /** Random tag soup from allowed, dangerous and table tags, end tags, text, references and comments. */
  @Test
  void fuzzedInputsGiveBalancedOutput() {
    String[] pieces = {
      "<p>", "</p>", "<div>", "</div>", "<b>", "</b>", "<i>", "</i>", "<a href=/x>", "<a href=#y id=y>",
      "</a>", "<ul>", "</ul>", "<ol start=2>", "</ol>", "<li>", "</li>", "<h1>", "</h1>", "<h3>",
      "<pre>", "</pre>", "\n", "<blockquote>", "<table>", "</table>", "<caption>", "<thead>",
      "<tbody>", "</tbody>", "<tr>", "</tr>", "<td colspan=2>", "</td>", "<th>", "<br>", "<hr>",
      "<img src=/i.png alt=x>", "<span class=\"a ng-x\">", "</span>", "<code>", "<del>", "<form>",
      "<svg>", "</svg>", "<math>", "<style>", "</style>", "<noscript>", "</noscript>", "<title>",
      "<select>", "<option>", "<template>", "</template>", "<!--", "-->", "<![CDATA[", "]]>",
      "text", " ", "&amp;", "&eacute;", "&#10;", "{{", "}}", "<", ">", "\"", "</", "<button>",
      "<nobr>", "<dd>", "<frameset>", "<textarea>", "<plaintext>", "<iframe>", "<xmp>",
    };
    Random random = new Random(262);
    for (int i = 0; i < 300; i++) {
      StringBuilder sb = new StringBuilder();
      int len = 1 + random.nextInt(25);
      for (int j = 0; j < len; j++) {
        sb.append(pieces[random.nextInt(pieces.length)]);
      }
      String input = sb.toString();
      for (Map.Entry<String, HtmlPolicy> p : POLICIES.entrySet()) {
        assertBalanced(p.getKey(), input, SafeHtml.sanitize(input, p.getValue()).html());
      }
    }
  }

  /** A sanity check of the check itself: hand-written unbalanced or misnested output is caught. */
  @Test
  void checkRejectsUnbalancedOutput() {
    String[] bad = {
      "<p>a<div>b</div></p>", "<p><p>x</p></p>", "<a href=\"/1\"><a href=\"/2\">x</a></a>",
      "<table>x<tr><td>y</td></tr></table>", "<table><tr><td>x</td></tr></table>",
      "<li>x</li><li>y</li>", "<b>x", "</div>", "<h1><h2>x</h2></h1>",
      "<div><li>a</li><div><li>b</li></div></div>",
    };
    for (String out : bad) {
      boolean failed;
      try {
        assertBalanced("hand", out, out);
        failed = false;
      } catch (AssertionError expected) {
        failed = true;
      }
      assertTrue(failed, () -> "the balance check accepted " + out);
    }
  }

  private static void assertBalanced(String policy, String input, String out) {
    String expected = canonical(naiveTree(out));
    for (SpecParser parser : SpecParsers.all()) {
      for (boolean scripting : new boolean[] {true, false}) {
        for (String[] ctx : CONTEXTS) {
          String doc = "<!DOCTYPE html><html><head></head><body>" + ctx[0] + out + ctx[1] + "</body></html>";
          Document d = parser.parse(doc, scripting);
          String where = policy + " / " + parser.name() + (scripting ? " scripting" : " no scripting")
              + " / " + ctx[0] + "\n input: " + input + "\n output: " + out;
          Element after = byId(d, "ta");
          assertNotNull(after, () -> "marker lost: " + where);
          List<Node> nodes = new ArrayList<>();
          Element parent = byId(d, "tp");
          if (parent != null) {
            for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
              nodes.add(c);
            }
            Node next = parent.getNextSibling();
            assertTrue(next == after || (next != null && next.getNodeType() == Node.TEXT_NODE && next.getNextSibling() == after),
                () -> "the output closed or moved its insertion point: " + where);
          } else {
            Element before = byId(d, "tb");
            assertNotNull(before, () -> "marker lost: " + where);
            Node c = before.getNextSibling();
            for (; c != null && c != after; c = c.getNextSibling()) {
              nodes.add(c);
            }
            assertTrue(c == after && after.getParentNode() == before.getParentNode(),
                () -> "the output moved the next element: " + where);
          }
          StringBuilder actual = new StringBuilder();
          for (Node node : nodes) {
            canonical(node, actual);
          }
          assertEquals(expected, actual.toString(), () -> "the parser did not keep the tree as written: " + where);
        }
      }
    }
  }

  private static Element byId(Document d, String id) {
    Deque<Node> todo = new ArrayDeque<>();
    todo.push(d.getDocumentElement());
    while (!todo.isEmpty()) {
      Node node = todo.pop();
      if (node instanceof Element && id.equals(((Element) node).getAttribute("id"))) {
        return (Element) node;
      }
      for (Node c = node.getLastChild(); c != null; c = c.getPreviousSibling()) {
        todo.push(c);
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // The tree the output's tags spell, built without HTML's tree-construction rules

  /** A node of the naive tree: an element (name, attributes, children), a text node or a comment. */
  private record Naive(String name, Map<String, String> attrs, List<Naive> children, String text) {}

  private static final Set<String> VOID =
      Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

  private static final Pattern TOKEN =
      Pattern.compile("<!---->|</([a-z0-9]+)>|<([a-z0-9]+)((?: [a-z]+(?:=\"[^\"]*\")?)*)>|[^<]+|<");

  private static final Pattern ATTR = Pattern.compile(" ([a-z]+)(?:=\"([^\"]*)\")?");

  private static List<Naive> naiveTree(String out) {
    Naive root = new Naive("#root", Map.of(), new ArrayList<>(), null);
    Deque<Naive> stack = new ArrayDeque<>();
    stack.push(root);
    Matcher m = TOKEN.matcher(out);
    boolean afterPre = false;
    while (m.find()) {
      String tok = m.group();
      boolean wasAfterPre = afterPre;
      afterPre = false;
      if (tok.equals("<!---->")) {
        stack.peek().children().add(new Naive("#comment", Map.of(), List.of(), ""));
      } else if (m.group(1) != null) {
        if (stack.size() == 1 || !stack.peek().name().equals(m.group(1))) {
          throw new AssertionError("unbalanced end tag " + tok + " in " + out);
        }
        stack.pop();
      } else if (m.group(2) != null) {
        Map<String, String> attrs = new TreeMap<>();
        Matcher a = ATTR.matcher(m.group(3));
        while (a.find()) {
          attrs.put(a.group(1), a.group(2) == null ? "" : decodeAttribute(a.group(2)));
        }
        Naive e = new Naive(m.group(2), attrs, new ArrayList<>(), null);
        stack.peek().children().add(e);
        if (!VOID.contains(e.name())) {
          stack.push(e);
        }
        afterPre = e.name().equals("pre");
      } else if (tok.equals("<")) {
        throw new AssertionError("raw < in " + out);
      } else {
        String text = decodeText(tok);
        if (wasAfterPre && text.startsWith("\n")) {
          text = text.substring(1); // the serialization rule for pre
        }
        List<Naive> siblings = stack.peek().children();
        if (!siblings.isEmpty() && siblings.get(siblings.size() - 1).text() != null
            && siblings.get(siblings.size() - 1).name().equals("#text")) {
          Naive last = siblings.remove(siblings.size() - 1);
          text = last.text() + text;
        }
        if (!text.isEmpty()) {
          siblings.add(new Naive("#text", Map.of(), List.of(), text));
        }
      }
    }
    if (stack.size() != 1) {
      throw new AssertionError("unclosed " + stack.peek().name() + " in " + out);
    }
    return root.children();
  }

  private static final Map<String, String> DECODED = new HashMap<>();

  /** Text as the spec parser decodes it (the output's references end in ';', so this is context-free). */
  private static String decodeText(String raw) {
    if (raw.indexOf('&') < 0) {
      return raw;
    }
    return DECODED.computeIfAbsent("t" + raw, k -> {
      Document d = SpecParsers.all().get(0).parse("<!DOCTYPE html><body><div>" + raw + "</div>", false);
      return d.getElementsByTagName("div").item(0).getTextContent();
    });
  }

  private static String decodeAttribute(String raw) {
    if (raw.indexOf('&') < 0) {
      return raw;
    }
    return DECODED.computeIfAbsent("a" + raw, k -> {
      Document d = SpecParsers.all().get(0).parse("<!DOCTYPE html><body><div title=\"" + raw + "\"></div>", false);
      return ((Element) d.getElementsByTagName("div").item(0)).getAttribute("title");
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Canonical forms

  private static String canonical(List<Naive> nodes) {
    StringBuilder sb = new StringBuilder();
    for (Naive n : nodes) {
      canonical(n, sb);
    }
    return sb.toString();
  }

  private static void canonical(Naive n, StringBuilder sb) {
    switch (n.name()) {
      case "#text" -> sb.append(esc(n.text()));
      case "#comment" -> sb.append("<!--").append(n.text()).append("-->");
      default -> {
        sb.append('<').append(n.name());
        n.attrs().forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(esc(v)).append('"'));
        sb.append('>');
        for (Naive c : n.children()) {
          canonical(c, sb);
        }
        if (!VOID.contains(n.name())) {
          sb.append("</").append(n.name()).append('>');
        }
      }
    }
  }

  private static void canonical(Node n, StringBuilder sb) {
    switch (n.getNodeType()) {
      case Node.TEXT_NODE -> sb.append(esc(n.getNodeValue()));
      case Node.COMMENT_NODE -> sb.append("<!--").append(n.getNodeValue()).append("-->");
      case Node.ELEMENT_NODE -> {
        String ns = n.getNamespaceURI();
        String name = n.getLocalName();
        if (!"http://www.w3.org/1999/xhtml".equals(ns)) {
          name = ns + ":" + name;
        }
        sb.append('<').append(name);
        Map<String, String> attrs = new TreeMap<>();
        NamedNodeMap map = n.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
          attrs.put(map.item(i).getNodeName(), map.item(i).getNodeValue());
        }
        attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(esc(v)).append('"'));
        sb.append('>');
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
          canonical(c, sb);
        }
        if (!VOID.contains(name)) {
          sb.append("</").append(name).append('>');
        }
      }
      default -> sb.append("<?node type ").append(n.getNodeType()).append('>');
    }
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  @Test
  void naiveTreeSeesEveryToken() {
    // the TOKEN pattern must not skip output it doesn't understand
    String out = SafeHtml.sanitize("<p class=language-a id=b title=\"&eacute;\">x<br>y</p>", HtmlPolicy.RICH).html();
    assertEquals("<p id=\"user-content-b\" class=\"language-a\" title=\"&eacute;\">x<br>y</p>", out);
    assertEquals("<p class=\"language-a\" id=\"user-content-b\" title=\"é\">x<br>y</p>", canonical(naiveTree(out)));
    assertFalse(canonical(naiveTree("a")).isEmpty());
  }
}
