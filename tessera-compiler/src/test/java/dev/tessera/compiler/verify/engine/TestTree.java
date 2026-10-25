package dev.tessera.compiler.verify.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.w3c.dom.Attr;
import org.w3c.dom.DocumentType;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * A minimal tree for the html5lib agreement test: built by {@link CurrentTreeBuilder} from the
 * shaded engine, and read from the oracle jar's DOM, then
 * serialized the same way (html5lib's indented format; adjacent text merged; attributes sorted;
 * no doctype, which the oracle's DOM leaves out).
 */
final class TestTree {
  final String namespace; // element namespace; null for document, text, comment, doctype
  final String name; // element local name, or doctype name
  final List<String> attributes = new ArrayList<>(); // "{ns} local=value"
  final List<TestTree> children = new ArrayList<>();
  final String text; // text or comment data
  final boolean comment;
  final boolean doctype;
  final String publicId;
  final String systemId;
  TestTree parent;

  private TestTree(String namespace, String name, String text, boolean comment, boolean doctype, String publicId, String systemId) {
    this.namespace = namespace;
    this.name = name;
    this.text = text;
    this.comment = comment;
    this.doctype = doctype;
    this.publicId = publicId;
    this.systemId = systemId;
  }

  static TestTree document() {
    return new TestTree(null, null, null, false, false, null, null);
  }

  static TestTree element(String namespace, String name) {
    return new TestTree(namespace, name, null, false, false, null, null);
  }

  static TestTree text(String text) {
    return new TestTree(null, null, text, false, false, null, null);
  }

  static TestTree comment(String text) {
    return new TestTree(null, null, text, true, false, null, null);
  }

  static TestTree doctype(String name, String publicId, String systemId) {
    return new TestTree(null, name, null, false, true, publicId, systemId);
  }

  void attribute(String uri, String local, String value) {
    attributes.add((uri == null || uri.isEmpty() ? "" : "{" + uri + "} ") + local + "=" + value);
  }

  void append(TestTree child) {
    detach(child);
    child.parent = this;
    children.add(child);
  }

  void insertBefore(TestTree child, TestTree before) {
    detach(child);
    child.parent = this;
    children.add(children.indexOf(before), child);
  }

  static void detach(TestTree node) {
    if (node.parent != null) {
      node.parent.children.remove(node);
      node.parent = null;
    }
  }

  /** Foster parenting: before the table if it has a parent, else into the stack parent. */
  static void foster(TestTree child, TestTree table, TestTree stackParent) {
    if (table.parent != null) {
      table.parent.insertBefore(child, table);
    } else {
      stackParent.append(child);
    }
  }

  static TestTree fromDom(Node node) {
    TestTree t;
    switch (node.getNodeType()) {
      case Node.DOCUMENT_NODE:
        t = document();
        break;
      case Node.DOCUMENT_TYPE_NODE:
        DocumentType d = (DocumentType) node;
        return doctype(d.getName(), d.getPublicId(), d.getSystemId());
      case Node.TEXT_NODE:
      case Node.CDATA_SECTION_NODE:
        return text(node.getNodeValue());
      case Node.COMMENT_NODE:
        return comment(node.getNodeValue());
      case Node.ELEMENT_NODE:
        t = element(node.getNamespaceURI(), node.getLocalName() != null ? node.getLocalName() : node.getNodeName());
        NamedNodeMap attrs = node.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
          Attr a = (Attr) attrs.item(i);
          t.attribute(a.getNamespaceURI(), a.getLocalName() != null ? a.getLocalName() : a.getName(), a.getValue());
        }
        break;
      default:
        throw new IllegalArgumentException("unexpected DOM node " + node);
    }
    for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
      t.append(fromDom(c));
    }
    return t;
  }

  String serialize() {
    StringBuilder sb = new StringBuilder();
    for (TestTree c : merged(children)) {
      c.serialize(sb, 0);
    }
    return sb.toString();
  }

  private void serialize(StringBuilder sb, int depth) {
    sb.append("| ");
    for (int i = 0; i < depth; i++) {
      sb.append("  ");
    }
    if (doctype) {
      sb.append("<!DOCTYPE ").append(name);
      if (!empty(publicId) || !empty(systemId)) {
        sb.append(" \"").append(n(publicId)).append("\" \"").append(n(systemId)).append('"');
      }
      sb.append(">\n");
      return;
    }
    if (comment) {
      sb.append("<!-- ").append(text).append(" -->\n");
      return;
    }
    if (text != null) {
      sb.append('"').append(text).append("\"\n");
      return;
    }
    String prefix = OpenElement.SVG.equals(namespace) ? "svg " : OpenElement.MATHML.equals(namespace) ? "math " : "";
    sb.append('<').append(prefix).append(name).append(">\n");
    for (String a : new TreeSet<>(attributes)) {
      sb.append("| ");
      for (int i = 0; i <= depth; i++) {
        sb.append("  ");
      }
      sb.append(a).append('\n');
    }
    for (TestTree c : merged(children)) {
      c.serialize(sb, depth + 1);
    }
  }

  private static boolean empty(String s) {
    return s == null || s.isEmpty();
  }

  private static String n(String s) {
    return s == null ? "" : s;
  }

  private static List<TestTree> merged(List<TestTree> nodes) {
    List<TestTree> out = new ArrayList<>();
    for (TestTree n : nodes) {
      if (n.doctype) {
        continue; // the oracle's JDK DOM drops it; quirks mode shows in the tree anyway
      }
      if (n.text != null && !n.comment && !out.isEmpty()) {
        TestTree last = out.get(out.size() - 1);
        if (last.text != null && !last.comment) {
          out.set(out.size() - 1, text(last.text + n.text));
          continue;
        }
      }
      out.add(n);
    }
    return out;
  }
}
