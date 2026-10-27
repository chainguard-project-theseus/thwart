package dev.tessera.compiler.codegen.attr;

import static dev.tessera.compiler.types.ExpressionCompiler.isDeclared;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.codegen.AttributeProcessor;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.Escapers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * {@code t:field="name"} / {@code t:field="*{name}"} inside {@code t:object}: checks the property path at build time and emits {@code id}, {@code name} and the bound value ({@code ctx.forms().value(...)}) for input/select/textarea; checkbox and radio get {@code checked}; select options are marked {@code selected}.
 *
 * <p>{@code id} is only added when the element has none; checkboxes and radios get a numbered
 * {@code id} ({@code roles1}, {@code roles2}, ..., counted per render by
 * {@code RenderContext.sequenceId}), as in Thymeleaf. Password inputs never
 * echo the bound value. A checkbox on a non-boolean field, a radio and an option are checked
 * (selected) when their value matches the bound value or, for a Collection, array or Map, one of
 * its elements, as in Thymeleaf. Checkboxes get Spring's {@code _name} hidden marker so an
 * unchecked box binds {@code false} (or an empty collection), unless the checkbox is disabled (a
 * {@code disabled} attribute, or a {@code t:disabled} that is true), as in Thymeleaf. A
 * {@code <select multiple>} gets the marker before it, with the same rule, as Thymeleaf's
 * {@code SpringSelectFieldTagProcessor} does.
 *
 * <p>Inside a fragment, {@code t:field="name"} where {@code name} is a {@code Field} parameter uses
 * the path the caller passed (and checked). Its value has static type {@code Object}, so a checkbox
 * bound that way is checked by its value attribute, like a non-boolean checkbox.
 */
public final class FieldProcessor implements AttributeProcessor {
  private static final String ESCAPERS = Escapers.class.getName();

  /**
   * Internal variable (unreachable from templates: contains {@code $}) holding the bound value of
   * the enclosing {@code <select t:field>}, used by {@link #option}.
   */
  static final String SELECTED = "select$value";

  /** Internal variables holding the path and object name of the enclosing {@code <select t:field>}. */
  static final String SELECTED_PATH = "select$path";

  static final String SELECTED_OBJECT = "select$object";

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
        input(ctx, attr, plan, field, display);
        break;
      case "textarea":
        nameAndId(plan, attr, name, id);
        // RCDATA, or TEXT for a textarea inside svg or math
        OutputContext content = Contexts.text(plan.element());
        plan.replaceBody(
            Emitted.hole(Skeleton.Escaper.forMethod(content.escaper()), plan.element().name(), null, attr.pos(), display),
            content);
        break;
      case "select":
        nameAndId(plan, attr, name, id);
        marker(ctx, attr, plan, name, true);
        // The options compare with the field's value, path and object name (evaluated once).
        String value = ctx.freshName("selected");
        String path = ctx.freshName("selectedPath");
        String object = ctx.freshName("selectedObject");
        plan.wrap(
            "{ java.lang.Object "
                + value
                + " = "
                + current.code()
                + "; java.lang.String "
                + path
                + " = "
                + name
                + "; java.lang.String "
                + object
                + " = "
                + field.objectName()
                + ";",
            "}");
        TypeMirror string = ctx.env().elements().getTypeElement("java.lang.String").asType();
        TypeMirror objectType = ctx.env().elements().getTypeElement("java.lang.Object").asType();
        plan.scope(
            plan.scope()
                .with(new Variable(SELECTED, objectType, value))
                .with(new Variable(SELECTED_PATH, string, path))
                .with(new Variable(SELECTED_OBJECT, string, object)));
        break;
      default:
        ctx.diagnostics()
            .error(attr.pos(), "t:field works on <input>, <select> and <textarea>, not <" + plan.element().name() + ">");
    }
  }

  private static void input(
      CodegenContext ctx, Attribute attr, ElementPlan plan, FormPaths.FormField field, String display) {
    CompiledExpr current = field.value();
    String name = field.path();
    String id = field.id();
    String type = staticValue(plan, "type").map(t -> Backports.strip(t).toLowerCase(Locale.ROOT)).orElse("text");
    switch (type) {
      case "checkbox":
        if (isBoolean(current.type())) {
          nameAndId(plan, attr, name, sequenceId(id));
          if (!plan.attribute("value").isPresent()) {
            plan.setAttribute("value", "\"true\"", attr.pos());
          }
          plan.setAttribute(
              "checked", "java.lang.Boolean.TRUE.equals(" + current.code() + ") ? \"checked\" : null", attr.pos());
          marker(ctx, attr, plan, name, false);
        } else if (checkedByValue(ctx, attr, plan, field)) {
          marker(ctx, attr, plan, name, false);
        }
        break;
      case "radio":
        checkedByValue(ctx, attr, plan, field);
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

  /**
   * Radio, or checkbox on a non-boolean field: checked when its value matches the bound value or,
   * for a Collection, array or Map, one of its elements ({@code Forms.selected}). Returns false
   * after reporting a missing value attribute.
   */
  private static boolean checkedByValue(
      CodegenContext ctx, Attribute attr, ElementPlan plan, FormPaths.FormField field) {
    String value = valueCode(plan, "value");
    if (value == null) {
      ctx.diagnostics()
          .error(attr.pos(), "t:field on a radio button or a non-boolean checkbox needs a value attribute (or t:value)");
      return false;
    }
    nameAndId(plan, attr, field.path(), sequenceId(field.id()));
    plan.setAttribute(
        "checked",
        "ctx.forms().selected("
            + field.objectName()
            + ", "
            + field.path()
            + ", "
            + field.value().code()
            + ", "
            + value
            + ") ? \"checked\" : null",
        attr.pos());
    return true;
  }

  /**
   * Spring's {@code _name} hidden marker, so that an unchecked box binds {@code false} (or an empty
   * collection) and a {@code <select multiple>} with nothing selected binds an empty collection:
   * {@code value="on"} after a checkbox, {@code value="1"} before a select ({@code select}), as
   * Thymeleaf's checkbox and select field processors write them. A select gets one only if it is
   * {@code multiple}. Like Thymeleaf, none for a disabled element (it is not submitted, so the
   * marker would reset the value): Thymeleaf checks for a {@code multiple} or {@code disabled}
   * attribute of any value after t:multiple/t:disabled/t:attr ran, as here (they precede t:field).
   */
  private static void marker(CodegenContext ctx, Attribute attr, ElementPlan plan, String name, boolean select) {
    List<String> conditions = new ArrayList<>();
    if (select) {
      Optional<ElementPlan.Attr> multiple = plan.attribute("multiple");
      if (!multiple.isPresent()) {
        return;
      }
      if (multiple.get() instanceof ElementPlan.DynamicAttr) {
        conditions.add(once(ctx, plan, (ElementPlan.DynamicAttr) multiple.get()) + " != null");
      }
    }
    Optional<ElementPlan.Attr> disabled = plan.attribute("disabled");
    if (disabled.isPresent()) {
      if (!(disabled.get() instanceof ElementPlan.DynamicAttr)) {
        return;
      }
      conditions.add(once(ctx, plan, (ElementPlan.DynamicAttr) disabled.get()) + " == null");
    }
    // Made up: no source position.
    Emitted marker =
        Emitted.concat(
            Emitted.text("<input type=\"hidden\" name=\"_", null),
            Emitted.code(" "),
            Emitted.hole(Skeleton.Escaper.ATTR, "input", "name", attr.pos(), name),
            Emitted.code(" "),
            Emitted.text(select ? "\" value=\"1\" />" : "\" value=\"on\" />", null));
    if (!conditions.isEmpty()) {
      marker =
          Emitted.opt(
              Skeleton.OptKind.CHECKBOX_MARKER,
              attr.pos(),
              "if (" + String.join(" && ", conditions) + ") { ",
              marker,
              " }");
    }
    if (select) {
      plan.beforeStartTag(marker);
    } else {
      plan.afterStartTag(marker);
    }
  }

  /**
   * Evaluates a computed attribute once, into a local the attribute and the marker share (null
   * omits the attribute, and is what the marker tests). Returns the local's name.
   */
  private static String once(CodegenContext ctx, ElementPlan plan, ElementPlan.DynamicAttr dynamic) {
    String local = ctx.freshName(dynamic.name());
    plan.wrap("{ java.lang.Object " + local + " = " + dynamic.valueCode() + ";", "}");
    plan.setAttribute(dynamic.name(), local, dynamic.pos());
    return local;
  }

  /**
   * Java code for {@code id} (Java code) numbered per render, as Thymeleaf's
   * {@code AbstractSpringFieldTagProcessor.computeId} numbers checkbox and radio ids. Evaluated only
   * when the element has no {@code id}, so an explicit id takes no number, as in Thymeleaf.
   */
  private static String sequenceId(String id) {
    return "ctx.sequenceId(" + id + ")";
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
   * inside a {@code <select t:field>}, sets {@code selected} when the option's value matches the
   * bound value or, for a Collection, array or Map ({@code <select multiple>}), one of its elements
   * ({@code Forms.selected}, like Spring's {@code SelectedValueComparator}: a null bound value
   * selects nothing). A static {@code selected} is replaced, as in Thymeleaf (a
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
    plan.setAttribute(
        "selected",
        "ctx.forms().selected("
            + plan.scope().lookup(SELECTED_OBJECT).get().javaName()
            + ", "
            + plan.scope().lookup(SELECTED_PATH).get().javaName()
            + ", "
            + selected.get().javaName()
            + ", "
            + value
            + ") ? \"selected\" : null",
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
