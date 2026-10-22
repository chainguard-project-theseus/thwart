package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.FragmentRef;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.type.TypeMirror;

/**
 * {@code t:insert} (keep host tag, fragment becomes the body) and {@code t:replace} (fragment replaces the host). Target is a fragment reference ({@code ~{tpl :: name(args)}}, same-file {@code ~{:: name}}) checked against the callee signature, or an expression of type {@code Fragment}. Fragment-typed arguments ({@code ~{::body}}, {@code ~{::name}}) become lambdas over the selected element.
 *
 * <p>Generated code calls the callee's static {@code fragment_<name>} method; a fragment never
 * sees the caller's variables, only its arguments. A {@code ~{::tag}} argument renders the
 * selected element in the caller's scope at the call site, through a private static helper
 * method that receives the visible variables as parameters.
 */
public final class InsertReplaceProcessor implements AttributeProcessor {
  private static final String FRAGMENT = "dev.tessera.runtime.Fragment";

  /**
   * Elements whose {@code ~{::selector}} helper is being generated, so mutually recursive
   * selections are reported instead of recursing forever. Generation is single-threaded.
   */
  private static final ThreadLocal<Set<Element>> SELECTING =
      ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    boolean replace = attr.localName().equals("replace");
    // t:insert writes HTML into the host's body; t:replace swaps the whole element.
    if (!replace && !UtextProcessor.requireHtmlText(ctx, attr, plan)) {
      return;
    }
    String code = code(ctx, attr, plan);
    // On error, render nothing in its place so the children do not cascade into more errors.
    String stmts = code == null ? "" : code;
    if (replace) {
      plan.replaceElement(stmts);
    } else {
      plan.replaceBody(stmts);
    }
  }

  private static String code(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (attr.value() == null || attr.value().isBlank()) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " needs a fragment reference, e.g. ~{fragments/layout :: header}");
      return null;
    }
    StringBuilder pre = new StringBuilder();
    if (attr.value().contains("::")) {
      FragmentRef ref = ctx.parser().parseFragmentRef(attr.value(), attr.valuePos(), ctx.diagnostics());
      if (ref == null) {
        return null;
      }
      Call call = resolve(ctx, ref, plan.scope(), plan.element(), pre);
      return call == null ? null : "{\n" + pre + call.invoke("ctx", "out") + ";\n}";
    }
    CompiledExpr e = ctx.compile(attr, plan.scope());
    if (e == null) {
      return null;
    }
    if (!ctx.env().types().isAssignable(e.type(), fragmentType(ctx))) {
      ctx.diagnostics()
          .error(
              e.pos(),
              attr.name()
                  + " needs a fragment reference (~{template :: name}) or a value of type Fragment, but the"
                  + " expression has type "
                  + e.type());
      return null;
    }
    String f = ctx.freshName("f");
    return "{ " + FRAGMENT + " " + f + " = " + e.code() + "; if (" + f + " != null) { " + f + ".render(ctx, out); } }";
  }

  /** A resolved call of {@code method(args..., ctx, out)}; {@code types} are the parameter types. */
  private record Call(String method, List<String> args, List<String> types) {
    String invoke(String ctxName, String outName) {
      List<String> all = new ArrayList<>(args);
      all.add(ctxName);
      all.add(outName);
      return method + "(" + String.join(", ", all) + ")";
    }
  }

  /**
   * Resolves {@code ref} to a fragment method and type-checks its arguments in {@code scope}.
   * Statements the argument codes depend on (Fragment lambdas) are appended to {@code pre}.
   * Reports and returns null on error.
   */
  private static Call resolve(CodegenContext ctx, FragmentRef ref, Scope scope, Element host, StringBuilder pre) {
    TemplateIndex.TemplateInfo target = ctx.template();
    if (ref.template() != null) {
      if (!insideRoot(ref.template())) {
        ctx.diagnostics()
            .error(
                ref.pos(),
                "template name '"
                    + ref.template()
                    + "' must be a path relative to the templates root, without a leading '/' or empty, '.'"
                    + " or '..' segments");
        return null;
      }
      target = ctx.index().template(ref.template()).orElse(null);
      if (target == null) {
        ctx.diagnostics()
            .error(
                ref.pos(),
                "unknown template '"
                    + ref.template()
                    + "' in ~{"
                    + ref.template()
                    + " :: "
                    + ref.selector()
                    + "}: template names are paths relative to the templates root, without .html");
        return null;
      }
    }
    TemplateIndex.FragmentInfo f = target.fragments().get(ref.selector());
    if (f == null) {
      ctx.diagnostics()
          .error(
              ref.pos(),
              "template '"
                  + target.viewName()
                  + "' has no fragment '"
                  + ref.selector()
                  + "'"
                  + (target.fragments().isEmpty()
                      ? ""
                      : "; its fragments are: "
                          + target.fragments().values().stream()
                              .map(InsertReplaceProcessor::signature)
                              .collect(Collectors.joining(", "))));
      return null;
    }
    String sig = target.viewName() + " :: " + signature(f);

    // Order the arguments like the parameters.
    List<FragmentRef.Arg> args = ref.args();
    List<FragmentRef.Arg> ordered = new ArrayList<>();
    boolean named = !args.isEmpty() && args.getFirst().name() != null;
    if (!named) {
      if (args.size() != f.params().size()) {
        ctx.diagnostics()
            .error(
                ref.pos(),
                "wrong number of arguments for fragment "
                    + sig
                    + ": expected "
                    + f.params().size()
                    + " but got "
                    + args.size());
        return null;
      }
      ordered.addAll(args);
    } else {
      Map<String, FragmentRef.Arg> byName = new HashMap<>();
      boolean ok = true;
      for (FragmentRef.Arg a : args) {
        if (f.params().stream().noneMatch(p -> p.name().equals(a.name()))) {
          ctx.diagnostics().error(a.pos(), "unknown argument '" + a.name() + "' for fragment " + sig);
          ok = false;
        } else if (byName.putIfAbsent(a.name(), a) != null) {
          ctx.diagnostics().error(a.pos(), "duplicate argument '" + a.name() + "' for fragment " + sig);
          ok = false;
        }
      }
      for (TemplateIndex.Param p : f.params()) {
        if (!byName.containsKey(p.name()) && ok) {
          ctx.diagnostics().error(ref.pos(), "missing argument '" + p.name() + "' for fragment " + sig);
          ok = false;
        }
        ordered.add(byName.get(p.name()));
      }
      if (!ok) {
        return null;
      }
    }

    List<String> codes = new ArrayList<>();
    boolean ok = true;
    for (int i = 0; i < ordered.size(); i++) {
      String code = argument(ctx, ordered.get(i), f.params().get(i), i, sig, scope, host, pre);
      ok &= code != null;
      codes.add(code);
    }
    List<String> types = f.params().stream().map(TemplateIndex.Param::javaType).toList();
    return ok ? new Call(target.className() + "." + f.methodName(), codes, types) : null;
  }

  private static String argument(
      CodegenContext ctx,
      FragmentRef.Arg arg,
      TemplateIndex.Param param,
      int index,
      String sig,
      Scope scope,
      Element host,
      StringBuilder pre) {
    String what = "argument " + (index + 1) + " ('" + param.name() + "') of fragment " + sig;
    boolean wantsFragment = isFragment(ctx, param.type());
    if (arg.value() instanceof Expr.FragmentExpr fe) {
      if (!wantsFragment) {
        ctx.diagnostics().error(arg.pos(), what + " has type " + param.javaType() + ", but a fragment ~{...} was passed");
        return null;
      }
      return fragmentValue(ctx, fe.ref(), scope, host, pre);
    }
    if (FormPaths.isField(param.type())) {
      return fieldArgument(ctx, arg, what, scope);
    }
    CompiledExpr e = ctx.expressions().compile(arg.value(), scope);
    if (e == null) {
      return null;
    }
    if (!ctx.env().types().isAssignable(e.type(), param.type())) {
      ctx.diagnostics().error(arg.pos(), what + " has type " + param.javaType() + ", but the argument has type " + e.type());
      return null;
    }
    return e.code();
  }

  /**
   * An argument for a {@code Field} parameter: a string literal field path, checked here against
   * the caller's {@code t:object} type, or a {@code Field} variable passed on (a fragment
   * forwarding its own parameter). Nothing else: the path is never computed at run time.
   */
  private static String fieldArgument(CodegenContext ctx, FragmentRef.Arg arg, String what, Scope scope) {
    if (arg.value() instanceof Expr.Literal lit && lit.value() instanceof String path) {
      if (scope.selection().isEmpty()) {
        ctx.diagnostics()
            .error(arg.pos(), what + " is a form field, so the call must be inside t:object (on this element or an ancestor)");
        return null;
      }
      CompiledExpr current = FormPaths.compile(path, lit.pos(), scope, ctx.expressions());
      if (current == null) {
        return null;
      }
      FormPaths.FormField f = FormPaths.literal(scope.selection().get(), path, current);
      return "new " + FormPaths.FIELD + "(" + f.objectName() + ", " + f.path() + ", " + current.code() + ")";
    }
    Optional<Variable> forwarded = FormPaths.fieldVariable(arg.value(), scope);
    if (forwarded.isPresent()) {
      return forwarded.get().javaName();
    }
    ctx.diagnostics()
        .error(
            arg.pos(),
            what + " has type Field: pass a field path as a string literal, e.g. 'lastName' (checked against the"
                + " enclosing t:object), or a Field parameter");
    return null;
  }

  /**
   * A {@code ~{...}} fragment argument: appends a local holding a {@code Fragment} lambda to
   * {@code pre} and returns its name. Same-file {@code ~{::x}} picks fragment {@code x}, or else
   * the first element whose tag is {@code x}.
   */
  private static String fragmentValue(CodegenContext ctx, FragmentRef ref, Scope scope, Element host, StringBuilder pre) {
    String c = ctx.freshName("ctx");
    String o = ctx.freshName("out");
    String local = ctx.freshName("frag");
    String call;
    if (ref.template() == null && !ctx.template().fragments().containsKey(ref.selector())) {
      call = selectedElement(ctx, ref, scope, host, pre, c, o);
    } else {
      Call target = resolve(ctx, ref, scope, host, pre);
      if (target == null) {
        return null;
      }
      // Evaluate the arguments now, into effectively final locals the lambda can capture.
      List<String> captured = new ArrayList<>();
      for (int i = 0; i < target.args().size(); i++) {
        String a = ctx.freshName("arg");
        pre.append("final ").append(target.types().get(i)).append(' ').append(a).append(" = ")
            .append(target.args().get(i)).append(";\n");
        captured.add(a);
      }
      call = new Call(target.method(), captured, target.types()).invoke(c, o);
    }
    if (call == null) {
      return null;
    }
    pre.append(FRAGMENT).append(' ').append(local).append(" = (").append(c).append(", ").append(o).append(") -> ")
        .append(call).append(";\n");
    return local;
  }

  /** {@code ~{::tag}}: a helper method rendering the first {@code <tag>} in the caller's scope. */
  private static String selectedElement(
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
    Set<Element> active = SELECTING.get();
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
      body = ctx.renderElement(target, scope, Set.of());
    } finally {
      active.remove(target);
    }
    String helper = ctx.freshName("select_" + ref.selector());
    List<String> decls = new ArrayList<>();
    List<String> captured = new ArrayList<>();
    for (Variable v : scope.variables()) {
      decls.add(WithProcessor.sourceType(v.type()) + " " + v.javaName());
      String a = ctx.freshName("cap");
      pre.append("final var ").append(a).append(" = ").append(v.javaName()).append(";\n");
      captured.add(a);
    }
    decls.add("dev.tessera.runtime.RenderContext ctx");
    decls.add("dev.tessera.runtime.Output out");
    ctx.addMember("  private static void " + helper + "(" + String.join(", ", decls) + ") {\n" + body + "  }");
    return new Call(helper, captured, List.of()).invoke(c, o);
  }

  private static Element find(List<Node> nodes, String tag) {
    for (Node n : nodes) {
      if (n instanceof Element e) {
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
      if (n instanceof Element child && contains(child, e)) {
        return true;
      }
    }
    return false;
  }

  /** {@code a/b} with no empty, {@code .} or {@code ..} segments (so no leading {@code /}). */
  static boolean insideRoot(String template) {
    for (String seg : template.split("/", -1)) {
      if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
        return false;
      }
    }
    return true;
  }

  /** {@code name(Type a, Type b)}. */
  private static String signature(TemplateIndex.FragmentInfo f) {
    return f.name()
        + "("
        + f.params().stream().map(p -> p.javaType() + " " + p.name()).collect(Collectors.joining(", "))
        + ")";
  }

  private static TypeMirror fragmentType(CodegenContext ctx) {
    return ctx.env().elements().getTypeElement(FRAGMENT).asType();
  }

  private static boolean isFragment(CodegenContext ctx, TypeMirror t) {
    return ctx.env().types().isSameType(t, fragmentType(ctx));
  }
}
