package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.TemplateIndex;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.FragmentRef;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.runtime.Fragment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
  private static final String FRAGMENT = Fragment.class.getName();

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
      plan.replaceBody(stmts, Contexts.text(plan.element()));
    }
  }

  private static String code(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    if (attr.value() == null || Backports.isBlank(attr.value())) {
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
  private static final class Call {
    private final String method;
    private final List<String> args;
    private final List<String> types;

    private Call(String method, List<String> args, List<String> types) {
      this.method = method;
      this.args = args;
      this.types = types;
    }

    public String method() {
      return method;
    }

    public List<String> args() {
      return args;
    }

    public List<String> types() {
      return types;
    }

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
    List<FragmentRef.Arg> ordered = orderArguments(ctx, ref, f, sig);
    if (ordered == null) {
      return null;
    }

    List<String> codes = new ArrayList<>();
    boolean ok = true;
    for (int i = 0; i < ordered.size(); i++) {
      String code = argument(ctx, ordered.get(i), f.params().get(i), i, sig, scope, host, pre);
      ok &= code != null;
      codes.add(code);
    }
    List<String> types = f.params().stream().map(TemplateIndex.Param::javaType).collect(Collectors.toList());
    return ok ? new Call(target.className() + "." + f.methodName(), codes, types) : null;
  }

  /**
   * {@code ref}'s arguments in the order of {@code f}'s parameters (all positional or all named).
   * Reports and returns null on a wrong count or an unknown, duplicate or missing name.
   */
  private static List<FragmentRef.Arg> orderArguments(
      CodegenContext ctx, FragmentRef ref, TemplateIndex.FragmentInfo f, String sig) {
    List<FragmentRef.Arg> args = ref.args();
    List<FragmentRef.Arg> ordered = new ArrayList<>();
    boolean named = !args.isEmpty() && args.get(0).name() != null;
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
    return ordered;
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
    if (arg.value() instanceof Expr.FragmentExpr) {
      if (!wantsFragment) {
        ctx.diagnostics().error(arg.pos(), what + " has type " + param.javaType() + ", but a fragment ~{...} was passed");
        return null;
      }
      return fragmentValue(ctx, ((Expr.FragmentExpr) arg.value()).ref(), scope, host, pre);
    }
    if (FormPaths.isField(param.type())) {
      return fieldArgument(ctx, arg, param, what, scope);
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
   * the caller's {@code t:object} type, a {@code Field} variable passed on (a fragment forwarding
   * its own parameter), or a sub-path of one ({@code name.answer}, #239). Nothing else: the path
   * is never computed from a run-time string. For a {@code Field<T>} parameter the field's type
   * must be assignable to {@code T}.
   */
  private static String fieldArgument(
      CodegenContext ctx, FragmentRef.Arg arg, TemplateIndex.Param param, String what, Scope scope) {
    TypeMirror wanted = FormPaths.fieldType(param.type());
    FormPaths.FormField f;
    if (arg.value() instanceof Expr.Literal && ((Expr.Literal) arg.value()).value() instanceof String) {
      Expr.Literal lit = (Expr.Literal) arg.value();
      String path = (String) lit.value();
      if (!scope.selection().isPresent()) {
        ctx.diagnostics()
            .error(arg.pos(), what + " is a form field, so the call must be inside t:object (on this element or an ancestor)");
        return null;
      }
      CompiledExpr current = FormPaths.compile(path, lit.pos(), scope, ctx.expressions());
      if (current == null) {
        return null;
      }
      f = FormPaths.literal(scope.selection().get(), path, current);
    } else if (FormPaths.fieldVariable(arg.value(), scope).isPresent()) {
      Variable forwarded = FormPaths.fieldVariable(arg.value(), scope).get();
      if (!ctx.env().types().isAssignable(forwarded.type(), param.type())) {
        ctx.diagnostics()
            .error(arg.pos(), what + " has type " + param.javaType() + ", but '" + forwarded.name() + "' is a " + forwarded.type());
        return null;
      }
      return forwarded.javaName();
    } else if (FormPaths.fieldRoot(arg.value(), scope).isPresent()) {
      f = FormPaths.resolveSubPath(arg.value(), FormPaths.fieldRoot(arg.value(), scope).get(), scope, ctx.expressions(), true);
      if (f == null) {
        return null;
      }
    } else {
      ctx.diagnostics()
          .error(
              arg.pos(),
              what + " has type Field: pass a field path as a string literal, e.g. 'lastName' (checked against the"
                  + " enclosing t:object), a Field parameter, or a property of one, e.g. name.answer");
      return null;
    }
    TypeMirror type = f.value().type();
    if (wanted != null && !ctx.env().types().isAssignable(type, wanted)) {
      ctx.diagnostics().error(arg.pos(), what + " has type " + param.javaType() + ", but the field has type " + type);
      return null;
    }
    String typeArg = wanted == null ? "java.lang.Object" : wanted.toString();
    return "new " + FormPaths.FIELD + "<" + typeArg + ">(" + f.objectName() + ", " + f.path() + ", " + f.value().code() + ")";
  }

  /**
   * A {@code ~{...}} fragment argument: appends a local holding a {@code Fragment} lambda to
   * {@code pre} and returns its name. Same-file {@code ~{::x}} picks fragment {@code x}, or else
   * the first element whose tag is {@code x}.
   */
  private static String fragmentValue(CodegenContext ctx, FragmentRef ref, Scope scope, Element host, StringBuilder pre) {
    // Wherever it is inserted, a table part at its top could end a cell or a foster-parented
    // element and move the text after it out of the table (#348).
    for (String n : FragmentTop.of(ctx, ref).elements) {
      if (FragmentTop.TABLE_PARTS.contains(n) && !n.equals("table")) {
        ctx.diagnostics()
            .error(
                ref.pos(),
                "~{" + (ref.template() == null ? "" : ref.template() + " ") + ":: " + ref.selector() + "} writes <" + n
                    + ">, which is not supported in a Fragment value: inserted in a <td> or a <div> a browser would"
                    + " close elements to place it and move text after it out of the table; insert the fragment"
                    + " with t:insert/t:replace directly in the table part that takes it");
        return null;
      }
    }
    String c = ctx.freshName("ctx");
    String o = ctx.freshName("out");
    String local = ctx.freshName("frag");
    String call;
    if (ref.template() == null && !ctx.template().fragments().containsKey(ref.selector())) {
      call = ElementSelection.helperCall(ctx, ref, scope, host, pre, c, o);
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
