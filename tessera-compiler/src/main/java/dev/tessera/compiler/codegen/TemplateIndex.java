package dev.tessera.compiler.codegen;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.types.ExpressionCompiler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * First pass over all templates: class names, resolved {@code t:params}, imports, and every
 * {@code t:fragment} signature, so templates can call each other's fragments with type checks.
 */
public final class TemplateIndex {
  private final List<TemplateInfo> templates;

  private TemplateIndex(List<TemplateInfo> templates) {
    this.templates = List.copyOf(templates);
  }

  /** A resolved parameter. {@code javaType} is source text usable in generated code. */
  public record Param(String name, TypeMirror type, String javaType) {}

  /**
   * A {@code t:fragment} definition.
   *
   * @param methodName static method on the template class, {@code fragment_<name>}
   */
  public record FragmentInfo(String name, List<Param> params, Element element, String methodName) {
    public FragmentInfo {
      params = List.copyOf(params);
    }
  }

  /**
   * One template.
   *
   * @param className fully qualified generated class name (see {@link ClassNames})
   * @param imports {@code t:import} entries
   * @param params resolved {@code t:params}
   * @param fragments fragments by name
   */
  public record TemplateInfo(
      Document document,
      String className,
      List<String> imports,
      List<Param> params,
      Map<String, FragmentInfo> fragments) {
    public TemplateInfo {
      imports = List.copyOf(imports);
      params = List.copyOf(params);
      fragments = Map.copyOf(fragments);
    }

    public String viewName() {
      return document.source().viewName();
    }
  }

  public static TemplateIndex build(
      List<Document> documents, ExpressionParser parser, ExpressionCompiler expressions, CompilerEnv env) {
    throw new UnsupportedOperationException("TODO(#5): TemplateIndex.build");
  }

  public static TemplateIndex of(List<TemplateInfo> templates) {
    return new TemplateIndex(templates);
  }

  public List<TemplateInfo> templates() {
    return templates;
  }

  public Optional<TemplateInfo> template(String viewName) {
    return templates.stream().filter(t -> t.viewName().equals(viewName)).findFirst();
  }
}
