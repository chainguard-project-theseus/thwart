package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.Diagnostics;
import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.codegen.AttributeProcessor;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.codegen.Emitted;
import dev.chainguard.thwart.compiler.codegen.TemplateIndex;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.expr.FragmentRef;
import dev.chainguard.thwart.compiler.forms.FormPaths;
import dev.chainguard.thwart.compiler.html.Contexts;
import dev.chainguard.thwart.compiler.html.ParsingContexts;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.Element;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.compiler.types.Scope;
import dev.chainguard.thwart.compiler.types.Variable;
import dev.chainguard.thwart.compiler.verify.Skeleton;
import dev.chainguard.thwart.runtime.Fragment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.lang.model.type.TypeMirror;

/**
 * {@code t:insert} (keep host tag, fragment becomes the body) and {@code t:replace} (fragment replaces the host). Target is a fragment reference ({@code ~{tpl :: name(args)}}, same-file {@code ~{:: name}}) checked against the callee signature, or an expression of type {@code Fragment}. Fragment-typed arguments ({@code ~{::body}}, {@code ~{::name}}) become lambdas over the selected element.
 *
 * <p>Generated code calls the callee's static method, or for a callee with a {@code Fragment}
 * parameter its private copy in the caller's class ({@link CodegenContext#privateFragment}); a
 * fragment never sees the caller's variables, only its arguments. A {@code ~{::tag}} argument renders the
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
    ParsingContexts contexts = ctx.template().parsingContexts();
    String point = replace ? contexts.place(plan.element()) : contexts.content(plan.element());
    // Written inside raw text, the raw-text rule (TemplateGenerator) reports this.
    boolean rawText = contexts.insideRawText(plan.element()) || (!replace && ParsingContexts.rawTextElement(plan.element()));
    Emitted code = rawText ? null : code(ctx, attr, plan, point);
    // On error, render nothing in its place so the children do not cascade into more errors.
    Emitted stmts = code == null ? Emitted.EMPTY : code;
    if (replace) {
      plan.replaceElement(stmts);
    } else {
      plan.replaceBody(stmts, Contexts.text(plan.element()));
    }
  }

  /** {@code point}: the {@link ParsingContexts} context the fragment's markup lands in. */
  private static Emitted code(CodegenContext ctx, Attribute attr, ElementPlan plan, String point) {
    if (attr.value() == null || JavaStrings.isJavaBlank(attr.value())) {
      ctx.diagnostics().error(attr.pos(), attr.name() + " needs a fragment reference, e.g. ~{fragments/layout :: header}");
      return null;
    }
    StringBuilder pre = new StringBuilder();
    if (attr.value().contains("::")) {
      FragmentRef ref = ctx.parser().parseFragmentRef(attr.value(), attr.valuePos(), ctx.diagnostics());
      if (ref == null) {
        return null;
      }
      // Whether the fragment's markup reads the same where it lands is the verifier's check: it
      // replays the callee's skeleton in this call's real parser state (ContextVerifier).
      Call call = resolve(ctx, ref, plan.scope(), plan.element(), pre);
      return call == null ? null : Emitted.wrap("{\n" + pre, call.emit(ref.pos()).then("\n"), "}");
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
    if (!point.equals(ParsingContexts.HTML)) {
      // A Fragment value is checked by the verifier in HTML content, where its candidates are replayed.
      ctx.diagnostics()
          .error(
              e.pos(),
              attr.name() + " with a Fragment value is only allowed in HTML content, not in "
                  + ParsingContexts.describe(point) + " content, where a browser parses the fragment's markup"
                  + " differently: move the " + attr.name() + " into HTML content");
      return null;
    }
    // Parsed again only for its shape: ctx.compile parsed it without errors.
    Expr value = ctx.parser().parseExpression(attr.value(), attr.valuePos(), new Diagnostics());
    Skeleton.Node candidates = FragmentCandidates.of(ctx, value, plan.scope(), attr.name());
    if (candidates == null) {
      return null;
    }
    String f = ctx.freshName("f");
    return Emitted.fragmentValue(JavaStrings.FRAGMENT_BODY, f, e.code(), candidates);
  }

  /**
   * A resolved call of {@code method(args..., ctx, out)}; {@code types} are the parameter types.
   * {@code fragmentArgs} the skeletons of the {@code Fragment} arguments known at build time.
   */
  private static final class Call {
    private final Skeleton.FragmentId target;
    private final String method;
    private final List<String> args;
    private final List<String> types;
    private final Map<String, Skeleton.Seq> fragmentArgs;

    private Call(
        Skeleton.FragmentId target, String method, List<String> args, List<String> types,
        Map<String, Skeleton.Seq> fragmentArgs) {
      this.target = target;
      this.method = method;
      this.args = args;
      this.types = types;
      this.fragmentArgs = fragmentArgs;
    }

    /** The call, writing to the template's output with the caller's {@code ctx}. */
    Emitted emit(SourcePos pos) {
      return Emitted.call(target, fragmentArgs, pos, method, args);
    }

    /** The skeleton of what the call writes, for a fragment argument. */
    Skeleton.Seq skeleton(SourcePos pos) {
      return new Skeleton.Seq(Collections.singletonList(new Skeleton.Call(target, fragmentArgs, pos)));
    }

    public List<String> args() {
      return args;
    }

    public List<String> types() {
      return types;
    }

    /** The call with {@code args} instead of its own, writing to {@code outName}: a Fragment lambda's body. */
    String invoke(List<String> args, String ctxName, String outName) {
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
    Map<String, Skeleton.Seq> fragmentArgs = new LinkedHashMap<>();
    boolean ok = true;
    for (int i = 0; i < ordered.size(); i++) {
      String code = argument(ctx, ordered.get(i), f.params().get(i), i, sig, scope, host, pre, fragmentArgs);
      ok &= code != null;
      codes.add(code);
    }
    List<String> types = f.params().stream().map(TemplateIndex.Param::javaType).collect(Collectors.toList());
    if (!ok) {
      return null;
    }
    return new Call(
        new Skeleton.FragmentId(target.viewName(), f.name()),
        FragmentMethods.takesFragment(f) ? ctx.privateFragment(target, f) : target.className() + "." + f.methodName(),
        codes, types, fragmentArgs);
  }

  /** Fragment parameters have no defaults (guide section 4.8). */
  private static final String NULL_FOR_OPTIONAL = "; every call passes every argument, `null` for an optional one";

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
                    + args.size()
                    + (args.size() < f.params().size() ? NULL_FOR_OPTIONAL : ""));
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
          ctx.diagnostics()
              .error(ref.pos(), "missing argument '" + p.name() + "' for fragment " + sig + NULL_FOR_OPTIONAL);
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

  /**
   * The code of an argument; for a {@code Fragment} parameter whose argument is known at build time
   * also puts its skeleton in {@code fragmentArgs}.
   */
  private static String argument(
      CodegenContext ctx,
      FragmentRef.Arg arg,
      TemplateIndex.Param param,
      int index,
      String sig,
      Scope scope,
      Element host,
      StringBuilder pre,
      Map<String, Skeleton.Seq> fragmentArgs) {
    String what = "argument " + (index + 1) + " ('" + param.name() + "') of fragment " + sig;
    boolean wantsFragment = TemplateIndex.isFragment(param.type());
    if (arg.value() instanceof Expr.FragmentExpr) {
      if (!wantsFragment) {
        ctx.diagnostics().error(arg.pos(), what + " has type " + param.type() + ", but a fragment ~{...} was passed");
        return null;
      }
      List<Skeleton.Node> skeleton = new ArrayList<>();
      String local = fragmentValue(ctx, ((Expr.FragmentExpr) arg.value()).ref(), scope, host, pre, skeleton);
      if (local != null) {
        fragmentArgs.put(param.name(), new Skeleton.Seq(skeleton));
      }
      return local;
    }
    if (FormPaths.isField(param.type())) {
      return fieldArgument(ctx, arg, param, what, scope);
    }
    CompiledExpr e = ctx.expressions().compile(arg.value(), scope);
    if (e == null) {
      return null;
    }
    if (!ctx.env().types().isAssignable(e.type(), param.type())) {
      ctx.diagnostics().error(arg.pos(), what + " has type " + param.type() + ", but the argument has type " + e.type());
      return null;
    }
    if (wantsFragment) {
      // A Fragment parameter, which the callee may insert. Bound to its candidates (a Fragment
      // parameter passed on: whatever the caller's caller bound; null: nothing), which the
      // verifier checks where the callee inserts the parameter.
      Skeleton.Node candidates = FragmentCandidates.of(ctx, arg.value(), scope, what);
      if (candidates == null) {
        return null;
      }
      fragmentArgs.put(param.name(), FragmentCandidates.seq(candidates));
    }
    return e.code();
  }

  /**
   * An argument for a {@code Field} parameter: a string literal field path, checked here against
   * the caller's {@code t:object} type, a {@code Field} variable passed on (a fragment forwarding
   * its own parameter), or a sub-path of one ({@code name.answer}). Nothing else: the path
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
            .error(arg.pos(), what + " has type " + param.type() + ", but '" + forwarded.name() + "' is a " + forwarded.type());
        return null;
      }
      return forwarded.javaName();
    } else if (FormPaths.fieldRoot(arg.value(), scope).isPresent()) {
      f = FormPaths.resolveSubPath(arg.value(), FormPaths.fieldRoot(arg.value(), scope).get(), scope, ctx.expressions(), FormPaths.Check.VALUE);
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
      ctx.diagnostics().error(arg.pos(), what + " has type " + param.type() + ", but the field has type " + type);
      return null;
    }
    String typeArg = wanted == null ? "java.lang.Object" : JavaStrings.typeName(wanted);
    return "new " + FormPaths.FIELD + "<" + typeArg + ">(" + f.objectName() + ", " + f.path() + ", " + f.value().code() + ")";
  }

  /**
   * A {@code ~{...}} fragment argument: appends a local holding a {@code Fragment} lambda to
   * {@code pre} and returns its name, and adds the skeleton of what the lambda writes to
   * {@code skeleton}. Same-file {@code ~{::x}} picks fragment {@code x}, or else the first element
   * whose tag is {@code x}. The lambda writes nothing here: its skeleton is the argument's, written
   * where the callee inserts the parameter ({@code Skeleton.ParamInsert}).
   */
  private static String fragmentValue(
      CodegenContext ctx, FragmentRef ref, Scope scope, Element host, StringBuilder pre, List<Skeleton.Node> skeleton) {
    String c = ctx.freshName("ctx");
    String o = ctx.freshName("out");
    String local = ctx.freshName("frag");
    String call;
    if (ref.template() == null && !ctx.template().fragments().containsKey(ref.selector())) {
      call = ElementSelection.helperCall(ctx, ref, scope, host, pre, c, o, skeleton);
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
      call = target.invoke(captured, c, o);
      skeleton.addAll(target.skeleton(ref.pos()).nodes());
    }
    if (call == null) {
      return null;
    }
    pre.append(JavaStrings.FRAGMENT_BODY).append(" ").append(local).append(" = (").append(c)
        .append(", ").append(o).append(") -> ").append(call).append(";\n");
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
        + f.params().stream().map(p -> p.type() + " " + p.name()).collect(Collectors.joining(", "))
        + ")";
  }

  private static TypeMirror fragmentType(CodegenContext ctx) {
    return ctx.env().elements().getTypeElement(FRAGMENT).asType();
  }
}
