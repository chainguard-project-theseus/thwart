package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.expr.FragmentRef;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Same-template {@code ~{::tag}} element selection: a private static helper method rendering
 * the first {@code <tag>} of the template in the caller's scope, with the visible variables
 * passed as parameters.
 */
final class ElementSelection {
  private ElementSelection() {}

  /**
   * Adds the helper for {@code ref} (selecting from {@code host}'s template) and returns the
   * call {@code helper(captured..., c, o)}; locals capturing the scope's variables are appended to
   * {@code pre}. Reports and returns null on error.
   */
  static String helperCall(
      CodegenContext ctx, FragmentRef ref, Scope scope, Element host, StringBuilder pre, String c, String o) {
    Element target = find(ctx.template().document().children(), ref.selector());
    if (target == null) {
      ctx.diagnostics()
          .error(
              ref.pos(),
              "~{::" + ref.selector() + "}: this template has no fragment or element named '" + ref.selector() + "'");
      return null;
    }
    if (!ref.args().isEmpty()) {
      ctx.diagnostics()
          .error(ref.pos(), "~{::" + ref.selector() + "} selects an element, which takes no arguments");
      return null;
    }
    if (contains(target, host)) {
      ctx.diagnostics()
          .error(
              ref.pos(),
              "~{::" + ref.selector() + "} selects an element that contains this t:insert/t:replace, so it would"
                  + " include itself");
      return null;
    }
    if (host.attribute(Attribute.PREFIX + "object").isPresent() && contains(host, target)) {
      ctx.diagnostics()
          .error(
              ref.pos(),
              "~{::" + ref.selector() + "} selects an element inside this element's t:object, but t:insert/t:replace"
                  + " runs before t:object, so the selection would render without it: move t:object to an"
                  + " enclosing element");
      return null;
    }
    Set<Element> active = ctx.selecting();
    if (!active.add(target)) {
      ctx.diagnostics()
          .error(
              ref.pos(),
              "~{::" + ref.selector() + "} and the selections inside it include each other, so rendering would"
                  + " never end: no selected element may pass ~{::...} of an element that selects it");
      return null;
    }
    String body;
    try {
      body = ctx.renderElement(target, scope, Backports.setOf());
    } finally {
      active.remove(target);
    }
    String helper = ctx.freshName("select_" + ref.selector());
    List<String> decls = new ArrayList<>();
    List<String> captured = new ArrayList<>();
    for (Variable v : scope.variables()) {
      String type = WithProcessor.sourceType(v.type());
      decls.add(type + " " + v.javaName());
      String a = ctx.freshName("cap");
      pre.append("final ").append(type).append(' ').append(a).append(" = ").append(v.javaName()).append(";\n");
      captured.add(a);
    }
    decls.add(FragmentMethods.IO);
    ctx.addMember("  private static void " + helper + "(" + String.join(", ", decls) + ") {\n" + body + "  }");
    captured.add(c);
    captured.add(o);
    return helper + "(" + String.join(", ", captured) + ")";
  }

  static Element find(List<Node> nodes, String tag) {
    for (Node n : nodes) {
      if (n instanceof Element) {
        Element e = (Element) n;
        if (e.name().toLowerCase(Locale.ROOT).equals(tag.toLowerCase(Locale.ROOT))) {
          return e;
        }
        Element inner = find(e.children(), tag);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  private static boolean contains(Element ancestor, Element e) {
    if (ancestor == e) {
      return true;
    }
    for (Node n : ancestor.children()) {
      if (n instanceof Element && contains((Element) n, e)) {
        return true;
      }
    }
    return false;
  }
}
