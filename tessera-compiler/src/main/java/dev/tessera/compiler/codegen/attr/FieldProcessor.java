package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
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
 * unchecked box binds {@code false}.
 *
 * <p>Inside a fragment, {@code t:field="name"} where {@code name} is a {@code Field} parameter uses
 * the path the caller passed (and checked). Its value has static type {@code Object}, so a checkbox
 * bound that way is checked by its value attribute, like a non-boolean checkbox.
 */
public final class FieldProcessor implements AttributeProcessor {
  private static final String ESCAPERS = Escapers.class.getName();

  /**
   * Internal variable (unreachable from templates: contains {@code $}) holding the bound display
   * value of the enclosing {@code <select t:field>}, used by {@link #option}.
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
    String display = "ctx.forms().value(" + field.objectName() + ", " + name + ", " + current.code() + ")";
    String tag = plan.element().name().toLowerCase(Locale.ROOT);
    switch (tag) {
      case "input" -> input(ctx, attr, plan, name, display, current);
      case "textarea" -> {
        nameAndId(plan, attr, name, true);
        plan.replaceBody(ESCAPERS + ".text(out, " + display + ");", OutputContext.TEXT);
      }
      case "select" -> {
        nameAndId(plan, attr, name, true);
        String local = ctx.freshName("selected");
        plan.wrap("{ java.lang.String " + local + " = " + display + ";", "}");
        TypeMirror string = ctx.env().elements().getTypeElement("java.lang.String").asType();
        plan.scope(plan.scope().with(new Variable(SELECTED, string, local)));
      }
      default ->
          ctx.diagnostics()
              .error(attr.pos(), "t:field works on <input>, <select> and <textarea>, not <" + plan.element().name() + ">");
    }
  }

  /** {@code name} is Java code (a String) for the field path. */
  private static void input(
      CodegenContext ctx, Attribute attr, ElementPlan plan, String name, String display, CompiledExpr current) {
    String type = staticValue(plan, "type").map(t -> t.strip().toLowerCase(Locale.ROOT)).orElse("text");
    switch (type) {
      case "checkbox" -> {
        if (isBoolean(current.type())) {
          nameAndId(plan, attr, name, true);
          if (plan.attribute("value").isEmpty()) {
            plan.setAttribute("value", "\"true\"", attr.pos());
          }
          plan.setAttribute(
              "checked", "java.lang.Boolean.TRUE.equals(" + current.code() + ") ? \"checked\" : null", attr.pos());
          plan.afterStartTag(
              "out.write("
                  + JavaStrings.literal("<input type=\"hidden\" name=\"_")
                  + "); "
                  + ESCAPERS
                  + ".attr(out, "
                  + name
                  + "); out.write("
                  + JavaStrings.literal("\" value=\"on\" />")
                  + ");");
        } else {
          checkedByValue(ctx, attr, plan, name, display);
        }
      }
      case "radio" -> checkedByValue(ctx, attr, plan, name, display);
      case "file" -> nameAndId(plan, attr, name, true);
      case "password" -> {
        nameAndId(plan, attr, name, true);
        plan.removeAttribute("value");
      }
      default -> {
        nameAndId(plan, attr, name, true);
        plan.setAttribute("value", display, attr.pos());
      }
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
    nameAndId(plan, attr, name, false);
    plan.setAttribute("checked", display + ".equals(" + value + ") ? \"checked\" : null", attr.pos());
  }

  private static void nameAndId(ElementPlan plan, Attribute attr, String name, boolean id) {
    if (id && plan.attribute("id").isEmpty()) {
      plan.setAttribute("id", name, attr.pos());
    }
    plan.setAttribute("name", name, attr.pos());
  }

  /**
   * Hook for every {@code <option>} (called by the generator after its attribute processors):
   * inside a {@code <select t:field>}, sets {@code selected} when the option's value equals the
   * bound value. Options without a value attribute are left unchanged.
   */
  public static void option(CodegenContext ctx, ElementPlan plan) {
    Optional<Variable> selected = plan.scope().lookup(SELECTED);
    if (selected.isEmpty()) {
      return;
    }
    String value = valueCode(plan, "value");
    if (value == null) {
      return;
    }
    plan.setAttribute(
        "selected",
        selected.get().javaName() + ".equals(" + value + ") ? \"selected\" : null",
        plan.element().pos());
  }

  /** Java code (type String) for an output attribute's value, or null if absent. */
  private static String valueCode(ElementPlan plan, String attribute) {
    return plan.attribute(attribute)
        .map(
            a ->
                a instanceof ElementPlan.DynamicAttr d
                    ? ESCAPERS + ".toText(" + d.valueCode() + ")"
                    : a.valueCode())
        .orElse(null);
  }

  private static Optional<String> staticValue(ElementPlan plan, String attribute) {
    return plan.attribute(attribute)
        .flatMap(a -> a instanceof ElementPlan.StaticAttr s ? Optional.ofNullable(s.source().value()) : Optional.empty());
  }

  private static boolean isBoolean(TypeMirror t) {
    return t.getKind() == TypeKind.BOOLEAN || isDeclared(t, "java.lang.Boolean");
  }

  /**
   * The field a {@code t:field}/{@code t:errors} attribute names: a {@code Field} variable (a
   * fragment parameter), or a plain property path ({@code lastName}, {@code *{lastName}},
   * {@code *{address.city}}) checked against the {@code t:object} selection in {@code scope}.
   * Reports and returns null on error. With {@code report} false it reports nothing, does not
   * compile the path, and leaves the value null.
   */
  static FormPaths.FormField field(CodegenContext ctx, Attribute attr, Scope scope, boolean report) {
    DiagnosticSink sink = report ? ctx.diagnostics() : d -> {};
    if (attr.value() == null || attr.value().isBlank()) {
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
    Optional<Scope.Selection> selection = scope.selection();
    if (selection.isEmpty()) {
      sink.error(
          attr.pos(),
          attr.name()
              + " can only be used inside t:object (on this element or an ancestor), or with a Field fragment"
              + " parameter");
      return null;
    }
    String path = FormPaths.pathOf(e);
    if (path == null) {
      sink.error(attr.valuePos(), attr.name() + " needs a field path such as lastName or *{address.city}, not an expression");
      return null;
    }
    if (!report) {
      return FormPaths.literal(selection.get(), path, null);
    }
    CompiledExpr current = FormPaths.compile(path, attr.valuePos(), scope, ctx.expressions());
    return current == null ? null : FormPaths.literal(selection.get(), path, current);
  }
}
