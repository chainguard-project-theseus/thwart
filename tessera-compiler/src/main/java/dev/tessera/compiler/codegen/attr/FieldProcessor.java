package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.runtime.Escapers;
import java.util.Locale;
import java.util.Optional;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * {@code t:field="name"} / {@code t:field="*{name}"} inside {@code t:object}: checks the property path at build time and emits {@code id}, {@code name} and the bound value ({@code ctx.forms().value(...)}) for input/select/textarea; checkbox and radio get {@code checked}; select options are marked {@code selected}.
 *
 * <p>{@code id} is only added when the element has none; checkboxes on non-boolean fields and
 * radios get no generated {@code id} (Thymeleaf numbers them per request). Password inputs never
 * echo the bound value. Boolean checkboxes get Spring's {@code _name} hidden marker so an
 * unchecked box binds {@code false}, unless the checkbox is disabled (a {@code disabled}
 * attribute, or a {@code t:disabled} that is true), as in Thymeleaf.
 *
 * <p>Inside a fragment, {@code t:field="name"} where {@code name} is a {@code Field} parameter uses
 * the path the caller passed (and checked). Its value has static type {@code Object}, so a checkbox
 * bound that way is checked by its value attribute, like a non-boolean checkbox.
 */
public final class FieldProcessor implements AttributeProcessor {
  private static final String ESCAPERS = Escapers.class.getName();

  /**
   * Internal variable (unreachable from templates: contains {@code $}) holding the bound display
   * value of the enclosing {@code <select t:field>} (null when the bound value is null), used by
   * {@link #option}.
   */
  static final String SELECTED = "select$value";

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    FormPaths.FormField field = field(ctx, attr, plan.scope(), true);
    if (field == null) {
      return;
    }
    CompiledExpr current = field.value();
    String name = field.path();
    String id = field.id();
    String display = "ctx.forms().value(" + field.objectName() + ", " + name + ", " + current.code() + ")";
    String tag = plan.element().name().toLowerCase(Locale.ROOT);
    switch (tag) {
      case "input":
        input(ctx, attr, plan, name, id, display, current);
        break;
      case "textarea":
        nameAndId(plan, attr, name, id);
        // RCDATA, or TEXT for a textarea inside svg or math
        OutputContext content = Contexts.text(plan.element());
        plan.replaceBody(ESCAPERS + "." + content.escaper() + "(out, " + display + ");", content);
        break;
      case "select":
        nameAndId(plan, attr, name, id);
        // Like Spring's SelectedValueComparator: a null bound value selects no option (not the
        // placeholder <option value="">, which a null's "" display value would match).
        String local = ctx.freshName("selected");
        plan.wrap(
            "{ java.lang.String "
                + local
                + " = ctx.forms().selectedValue("
                + field.objectName()
                + ", "
                + name
                + ", "
                + current.code()
                + ");",
            "}");
        TypeMirror string = ctx.env().elements().getTypeElement("java.lang.String").asType();
        plan.scope(plan.scope().with(new Variable(SELECTED, string, local)));
        break;
      default:
        ctx.diagnostics()
            .error(attr.pos(), "t:field works on <input>, <select> and <textarea>, not <" + plan.element().name() + ">");
    }
  }

  /** {@code name} and {@code id} are Java code (Strings) for the field path and element id. */
  private static void input(
      CodegenContext ctx,
      Attribute attr,
      ElementPlan plan,
      String name,
      String id,
      String display,
      CompiledExpr current) {
    String type = staticValue(plan, "type").map(t -> Backports.strip(t).toLowerCase(Locale.ROOT)).orElse("text");
    switch (type) {
      case "checkbox":
        if (isBoolean(current.type())) {
          nameAndId(plan, attr, name, id);
          if (!plan.attribute("value").isPresent()) {
            plan.setAttribute("value", "\"true\"", attr.pos());
          }
          plan.setAttribute(
              "checked", "java.lang.Boolean.TRUE.equals(" + current.code() + ") ? \"checked\" : null", attr.pos());
          String marker =
              "out.write("
                  + JavaStrings.literal("<input type=\"hidden\" name=\"_")
                  + "); "
                  + ESCAPERS
                  + ".attr(out, "
                  + name
                  + "); out.write("
                  + JavaStrings.literal("\" value=\"on\" />")
                  + ");";
          // Like Thymeleaf, no marker for a disabled checkbox (it is not submitted, so the marker
          // would reset the value to false). Thymeleaf checks for a disabled attribute of any
          // value after t:disabled/t:attr ran, as here (they precede t:field).
          Optional<ElementPlan.Attr> disabled = plan.attribute("disabled");
          if (!disabled.isPresent()) {
            plan.afterStartTag(marker);
          } else if (disabled.get() instanceof ElementPlan.DynamicAttr) {
            // Evaluate once: the attribute and the marker share the value (null omits both ways).
            ElementPlan.DynamicAttr dynamic = (ElementPlan.DynamicAttr) disabled.get();
            String local = ctx.freshName("disabled");
            plan.wrap("{ java.lang.Object " + local + " = " + dynamic.valueCode() + ";", "}");
            plan.setAttribute(dynamic.name(), local, dynamic.pos());
            plan.afterStartTag("if (" + local + " == null) { " + marker + " }");
          }
        } else {
          checkedByValue(ctx, attr, plan, name, display);
        }
        break;
      case "radio":
        checkedByValue(ctx, attr, plan, name, display);
        break;
      case "file":
        nameAndId(plan, attr, name, id);
        break;
      case "password":
        nameAndId(plan, attr, name, id);
        plan.removeAttribute("value");
        break;
      default:
        nameAndId(plan, attr, name, id);
        plan.setAttribute("value", display, attr.pos());
    }
  }

  /** Radio, or checkbox on a non-boolean field: checked when its value equals the bound value. */
  private static void checkedByValue(CodegenContext ctx, Attribute attr, ElementPlan plan, String name, String display) {
    String value = valueCode(plan, "value");
    if (value == null) {
      ctx.diagnostics()
          .error(attr.pos(), "t:field on a radio button or a non-boolean checkbox needs a value attribute (or t:value)");
      return;
    }
    nameAndId(plan, attr, name, null);
    plan.setAttribute("checked", display + ".equals(" + value + ") ? \"checked\" : null", attr.pos());
  }

  /** {@code name} and {@code id} are Java code (Strings); a null {@code id} adds no id. */
  private static void nameAndId(ElementPlan plan, Attribute attr, String name, String id) {
    if (id != null && !plan.attribute("id").isPresent()) {
      plan.setAttribute("id", id, attr.pos());
    }
    plan.setAttribute("name", name, attr.pos());
  }

  /**
   * Hook for every {@code <option>} (called by the generator after its attribute processors):
   * inside a {@code <select t:field>}, sets {@code selected} when the option's value equals the
   * bound value (never when the bound value is null, like Spring's
   * {@code SelectedValueComparator}). A static {@code selected} is replaced, as in Thymeleaf (a
   * natural-template mock-up), but an option with a {@code t:selected} keeps it: the explicit
   * expression wins. Options without a value attribute are left unchanged.
   */
  public static void option(CodegenContext ctx, ElementPlan plan) {
    Optional<Variable> selected = plan.scope().lookup(SELECTED);
    if (!selected.isPresent()
        || plan.attribute("selected").filter(a -> a instanceof ElementPlan.DynamicAttr).isPresent()) {
      return;
    }
    String value = valueCode(plan, "value");
    if (value == null) {
      return;
    }
    String local = selected.get().javaName();
    plan.setAttribute(
        "selected",
        "(" + local + " != null && " + local + ".equals(" + value + ")) ? \"selected\" : null",
        plan.element().pos());
  }

  /** Java code (type String) for an output attribute's value, or null if absent. */
  private static String valueCode(ElementPlan plan, String attribute) {
    return plan.attribute(attribute)
        .map(
            a ->
                a instanceof ElementPlan.DynamicAttr
                    ? ESCAPERS + ".toText(" + a.valueCode() + ")"
                    : a.valueCode())
        .orElse(null);
  }

  private static Optional<String> staticValue(ElementPlan plan, String attribute) {
    return plan.attribute(attribute)
        .filter(a -> a instanceof ElementPlan.StaticAttr)
        .map(a -> ((ElementPlan.StaticAttr) a).source().value());
  }

  private static boolean isBoolean(TypeMirror t) {
    return t.getKind() == TypeKind.BOOLEAN || isDeclared(t, "java.lang.Boolean");
  }

  /**
   * The field a {@code t:field}/{@code t:errors} attribute names: a {@code Field} variable (a
   * fragment parameter) or a sub-path of one ({@code name.answer}, {@link FormPaths#resolveSubPath}),
   * or a property path ({@code lastName}, {@code *{lastName}}, {@code *{address.city}},
   * {@code *{items[i].name}}) checked against the {@code t:object} selection in {@code scope}
   * ({@link FormPaths#resolve}). Reports and returns null on error. With
   * {@code report} false it reports nothing, compiles only the path's index expressions, and
   * leaves the value null.
   */
  static FormPaths.FormField field(CodegenContext ctx, Attribute attr, Scope scope, boolean report) {
    DiagnosticSink sink = report ? ctx.diagnostics() : d -> {};
    if (attr.value() == null || Backports.isBlank(attr.value())) {
      sink.error(attr.pos(), attr.name() + " needs a field path, e.g. " + attr.name() + "=\"*{lastName}\"");
      return null;
    }
    Expr e = ctx.parser().parseExpression(attr.value(), attr.valuePos(), sink);
    if (e == null) {
      return null;
    }
    Optional<Variable> variable = FormPaths.fieldVariable(e, scope);
    if (variable.isPresent()) {
      return FormPaths.fromVariable(variable.get(), attr.valuePos(), ctx.expressions());
    }
    Optional<Variable> fieldRoot = FormPaths.fieldRoot(e, scope);
    Optional<Scope.Selection> selection = scope.selection();
    if (!fieldRoot.isPresent() && !selection.isPresent()) {
      sink.error(
          attr.pos(),
          attr.name()
              + " can only be used inside t:object (on this element or an ancestor), or with a Field fragment"
              + " parameter");
      return null;
    }
    if (!FormPaths.isPath(e)) {
      sink.error(
          attr.valuePos(),
          attr.name() + " needs a field path such as lastName, *{address.city} or *{items[i].name}, not an expression");
      return null;
    }
    // Without reporting, compile through a compiler whose diagnostics go nowhere: t:field reports.
    ExpressionCompiler expressions =
        report
            ? ctx.expressions()
            : new ExpressionCompiler(
                new CompilerEnv(
                    ctx.env().elements(),
                    ctx.env().types(),
                    d -> {},
                    ctx.env().basePackage(),
                    ctx.env().messages(),
                    ctx.env().functionNamespaces(),
                    ctx.env().functionIndex()));
    return fieldRoot.isPresent()
        ? FormPaths.resolveSubPath(e, fieldRoot.get(), scope, expressions, report)
        : FormPaths.resolve(e, scope, expressions, report);
  }
}
