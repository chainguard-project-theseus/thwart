package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.TemplateGenerator;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
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
 */
public final class FieldProcessor implements AttributeProcessor {
  private static final String RT = "dev.tessera.runtime.";

  /**
   * Internal variable (unreachable from templates: contains {@code $}) holding the bound display
   * value of the enclosing {@code <select t:field>}, used by {@link #option}.
   */
  static final String SELECTED = "select$value";

  @Override
  public void process(CodegenContext ctx, Attribute attr, ElementPlan plan) {
    Scope scope = plan.scope();
    Optional<Scope.Selection> selection = scope.selection();
    if (selection.isEmpty()) {
      ctx.diagnostics().error(attr.pos(), "t:field can only be used inside t:object (on this element or an ancestor)");
      return;
    }
    String path = path(ctx, attr, ctx.diagnostics());
    if (path == null) {
      return;
    }
    CompiledExpr current = FormPaths.compile(path, attr.valuePos(), scope, ctx.expressions());
    if (current == null) {
      return;
    }
    String name = TemplateGenerator.javaString(path);
    String display =
        "ctx.forms().value("
            + TemplateGenerator.javaString(selection.get().objectName())
            + ", "
            + name
            + ", "
            + current.code()
            + ")";
    String tag = plan.element().name().toLowerCase(Locale.ROOT);
    switch (tag) {
      case "input" -> input(ctx, attr, plan, path, name, display, current);
      case "textarea" -> {
        nameAndId(plan, attr, path, name, true);
        plan.replaceBody(RT + "Escapers.text(out, " + display + ");");
      }
      case "select" -> {
        nameAndId(plan, attr, path, name, true);
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

  private static void input(
      CodegenContext ctx, Attribute attr, ElementPlan plan, String path, String name, String display, CompiledExpr current) {
    String type = staticValue(plan, "type").map(t -> t.strip().toLowerCase(Locale.ROOT)).orElse("text");
    switch (type) {
      case "checkbox" -> {
        if (isBoolean(current.type())) {
          nameAndId(plan, attr, path, name, true);
          if (plan.attribute("value").isEmpty()) {
            plan.setAttribute("value", "\"true\"", OutputContext.ATTRIBUTE, attr.pos());
          }
          plan.setAttribute(
              "checked", "java.lang.Boolean.TRUE.equals(" + current.code() + ") ? \"checked\" : null", OutputContext.ATTRIBUTE, attr.pos());
          plan.afterStartTag(
              "out.write("
                  + TemplateGenerator.javaString("<input type=\"hidden\" name=\"_" + path + "\" value=\"on\" />")
                  + ");");
        } else {
          checkedByValue(ctx, attr, plan, path, name, display);
        }
      }
      case "radio" -> checkedByValue(ctx, attr, plan, path, name, display);
      case "file" -> nameAndId(plan, attr, path, name, true);
      case "password" -> {
        nameAndId(plan, attr, path, name, true);
        plan.removeAttribute("value");
      }
      default -> {
        nameAndId(plan, attr, path, name, true);
        plan.setAttribute("value", display, OutputContext.ATTRIBUTE, attr.pos());
      }
    }
  }

  /** Radio, or checkbox on a non-boolean field: checked when its value equals the bound value. */
  private static void checkedByValue(
      CodegenContext ctx, Attribute attr, ElementPlan plan, String path, String name, String display) {
    String value = valueCode(plan, "value");
    if (value == null) {
      ctx.diagnostics()
          .error(attr.pos(), "t:field on a radio button or a non-boolean checkbox needs a value attribute (or t:value)");
      return;
    }
    nameAndId(plan, attr, path, name, false);
    plan.setAttribute("checked", display + ".equals(" + value + ") ? \"checked\" : null", OutputContext.ATTRIBUTE, attr.pos());
  }

  private static void nameAndId(ElementPlan plan, Attribute attr, String path, String name, boolean id) {
    if (id && plan.attribute("id").isEmpty()) {
      plan.setAttribute("id", name, OutputContext.ATTRIBUTE, attr.pos());
    }
    plan.setAttribute("name", name, OutputContext.ATTRIBUTE, attr.pos());
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
        OutputContext.ATTRIBUTE,
        plan.element().pos());
  }

  /** Java code (type String) for an output attribute's value, or null if absent. */
  private static String valueCode(ElementPlan plan, String attribute) {
    return plan.attribute(attribute)
        .map(
            a ->
                switch (a) {
                  case ElementPlan.StaticAttr s ->
                      TemplateGenerator.javaString(s.source().value() == null ? "" : s.source().value());
                  case ElementPlan.DynamicAttr d -> RT + "Escapers.toText(" + d.valueCode() + ")";
                })
        .orElse(null);
  }

  private static Optional<String> staticValue(ElementPlan plan, String attribute) {
    return plan.attribute(attribute)
        .flatMap(a -> a instanceof ElementPlan.StaticAttr s ? Optional.ofNullable(s.source().value()) : Optional.empty());
  }

  private static boolean isBoolean(TypeMirror t) {
    return t.getKind() == TypeKind.BOOLEAN || t.toString().equals("java.lang.Boolean");
  }

  /**
   * The dotted field path of a {@code t:field}/{@code t:errors} attribute ({@code lastName},
   * {@code *{lastName}}, {@code *{address.city}}). Reports to {@code sink} and returns null if
   * the value is not a plain property path.
   */
  static String path(CodegenContext ctx, Attribute attr, DiagnosticSink sink) {
    if (attr.value() == null || attr.value().isBlank()) {
      sink.error(attr.pos(), attr.name() + " needs a field path, e.g. " + attr.name() + "=\"*{lastName}\"");
      return null;
    }
    Expr e = ctx.parser().parseExpression(attr.value(), attr.valuePos(), sink);
    if (e == null) {
      return null;
    }
    String path = FormPaths.pathOf(e);
    if (path == null) {
      sink.error(attr.valuePos(), attr.name() + " needs a field path such as lastName or *{address.city}, not an expression");
    }
    return path;
  }
}
