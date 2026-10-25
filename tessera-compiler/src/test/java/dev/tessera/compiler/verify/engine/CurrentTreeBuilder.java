package dev.tessera.compiler.verify.engine;

import dev.tessera.compiler.internal.html5.current.common.XmlViolationPolicy;
import dev.tessera.compiler.internal.html5.current.impl.HtmlAttributes;
import dev.tessera.compiler.internal.html5.current.impl.Tokenizer;
import dev.tessera.compiler.internal.html5.current.impl.TreeBuilder;
import dev.tessera.compiler.internal.html5.current.impl.UTF16Buffer;
import org.xml.sax.SAXException;

/**
 * Builds a {@link TestTree} with the shaded current engine, set up like the oracle's
 * {@code HtmlDocumentBuilder} in {@code SpecParsers}. {@link ClassicTreeBuilder} is the same for the
 * classic engine.
 */
final class CurrentTreeBuilder extends TreeBuilder<TestTree> {
  static {
    Engines.all(); // first, so the engine classes initialize with their asserts off, as in production
  }

  private final TestTree document = TestTree.document();

  static String parse(String html, boolean scripting) throws SAXException {
    CurrentTreeBuilder tb = new CurrentTreeBuilder();
    tb.setScriptingEnabled(scripting);
    tb.setIgnoringComments(false);
    tb.setNamePolicy(XmlViolationPolicy.ALTER_INFOSET);
    Tokenizer tok = new Tokenizer(tb, false);
    tok.setCommentPolicy(XmlViolationPolicy.ALLOW);
    tok.setContentNonXmlCharPolicy(XmlViolationPolicy.ALLOW);
    tok.setContentSpacePolicy(XmlViolationPolicy.ALLOW);
    tok.setNamePolicy(XmlViolationPolicy.ALTER_INFOSET);
    tok.setXmlnsPolicy(XmlViolationPolicy.ALTER_INFOSET);
    tok.start();
    char[] chars = html.toCharArray();
    UTF16Buffer buf = new UTF16Buffer(chars, 0, chars.length);
    boolean lastCR = false;
    while (buf.hasMore()) {
      buf.adjust(lastCR);
      lastCR = false;
      if (buf.hasMore()) {
        lastCR = tok.tokenizeBuffer(buf);
      }
    }
    tok.eof();
    tok.end();
    return tb.document.serialize();
  }

  private static TestTree element(String ns, String name, HtmlAttributes attributes) {
    TestTree e = TestTree.element(ns, name);
    addAttributes(e, attributes);
    return e;
  }

  private static void addAttributes(TestTree e, HtmlAttributes attributes) {
    for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
      e.attribute(attributes.getURINoBoundsCheck(i), attributes.getLocalNameNoBoundsCheck(i), attributes.getValueNoBoundsCheck(i));
    }
  }

  @Override
  protected void appendDoctypeToDocument(String name, String publicIdentifier, String systemIdentifier) {
    document.append(TestTree.doctype(name, publicIdentifier, systemIdentifier));
  }

  @Override
  protected TestTree createElement(String ns, String name, HtmlAttributes attributes, TestTree intendedParent) {
    return element(ns, name, attributes);
  }

  @Override
  protected TestTree createHtmlElementSetAsRoot(HtmlAttributes attributes) {
    TestTree e = element(OpenElement.HTML, "html", attributes);
    document.append(e);
    return e;
  }

  @Override
  protected void detachFromParent(TestTree element) {
    TestTree.detach(element);
  }

  @Override
  protected boolean hasChildren(TestTree element) {
    return !element.children.isEmpty();
  }

  @Override
  protected void appendElement(TestTree child, TestTree newParent) {
    newParent.append(child);
  }

  @Override
  protected void appendChildrenToNewParent(TestTree oldParent, TestTree newParent) {
    for (TestTree c : new java.util.ArrayList<>(oldParent.children)) {
      newParent.append(c);
    }
  }

  @Override
  protected void insertFosterParentedChild(TestTree child, TestTree table, TestTree stackParent) {
    TestTree.foster(child, table, stackParent);
  }

  @Override
  protected TestTree createAndInsertFosterParentedElement(
      String ns, String name, HtmlAttributes attributes, TestTree table, TestTree stackParent) {
    TestTree e = element(ns, name, attributes);
    TestTree.foster(e, table, stackParent);
    return e;
  }

  @Override
  protected void insertFosterParentedCharacters(char[] buf, int start, int length, TestTree table, TestTree stackParent) {
    TestTree.foster(TestTree.text(new String(buf, start, length)), table, stackParent);
  }

  @Override
  protected void appendCharacters(TestTree parent, char[] buf, int start, int length) {
    parent.append(TestTree.text(new String(buf, start, length)));
  }

  @Override
  protected void appendComment(TestTree parent, char[] buf, int start, int length) {
    parent.append(TestTree.comment(new String(buf, start, length)));
  }

  @Override
  protected void appendCommentToDocument(char[] buf, int start, int length) {
    document.append(TestTree.comment(new String(buf, start, length)));
  }

  @Override
  protected void addAttributesToElement(TestTree element, HtmlAttributes attributes) {
    for (int i = 0; i < attributes.getLength(); i++) {
      String local = attributes.getLocalNameNoBoundsCheck(i);
      String uri = attributes.getURINoBoundsCheck(i);
      String key = (uri == null || uri.isEmpty() ? "" : "{" + uri + "} ") + local + "=";
      boolean present = false;
      for (String a : element.attributes) {
        present |= a.startsWith(key);
      }
      if (!present) {
        element.attribute(uri, local, attributes.getValueNoBoundsCheck(i));
      }
    }
  }
}
