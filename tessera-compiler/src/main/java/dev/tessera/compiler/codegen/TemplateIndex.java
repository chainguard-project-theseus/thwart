package dev.tessera.compiler.codegen;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.expr.FragmentSignature;
import dev.tessera.compiler.expr.ParamDecl;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.types.ExpressionCompiler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  /**
   * Indexes every document. Problems (a valueless or unparsable {@code t:import} or {@code
   * t:params}, unresolvable or unsafe types, duplicate parameter or fragment names) are reported
   * to {@code env} and the affected imports, parameters or fragments are left out. A class-name
   * collision is reported too, but the template is still indexed: the error stops all output.
   */
  public static TemplateIndex build(
      List<Document> documents, ExpressionParser parser, ExpressionCompiler expressions, CompilerEnv env) {
    DiagnosticSink sink = env.diagnostics();
    List<TemplateInfo> out = new ArrayList<>();
    Map<String, String> classOwners = new HashMap<>();
    for (Document doc : documents) {
      String className = ClassNames.className(env.basePackage(), doc.source().viewName());
      // Case-insensitive: class files of both would clash on case-insensitive file systems.
      String previous = classOwners.putIfAbsent(className.toLowerCase(Locale.ROOT), doc.source().path());
      if (previous != null) {
        sink.error(
            new SourcePos(doc.source().path(), 1, 1),
            "generated class name "
                + className
                + " collides with the one generated for "
                + previous
                + "; rename one of the templates");
      }
      Element root = doc.rootElement();
      List<String> imports = List.of();
      List<Param> params = List.of();
      if (root != null) {
        Attribute imp = root.attribute(Attribute.PREFIX + "import").orElse(null);
        if (imp != null && imp.value() == null) {
          sink.error(imp.pos(), imp.name() + " needs a value, e.g. t:import=\"java.util.List\"");
        } else if (imp != null) {
          List<String> parsed = parser.parseImports(imp.value(), imp.valuePos(), sink);
          imports = parsed == null ? List.of() : parsed;
        }
        Attribute par = root.attribute(Attribute.PREFIX + "params").orElse(null);
        if (par != null && par.value() == null) {
          sink.error(par.pos(), par.name() + " needs a value, e.g. t:params=\"String name, int count\"");
        } else if (par != null) {
          List<ParamDecl> decls = parser.parseParams(par.value(), par.valuePos(), sink);
          params = resolve(decls == null ? List.of() : decls, imports, expressions, sink);
          params = withoutFields(params, decls, sink);
        }
      }
      Map<String, FragmentInfo> fragments = new LinkedHashMap<>();
      collectFragments(doc.children(), imports, parser, expressions, sink, fragments);
      out.add(new TemplateInfo(doc, className, imports, params, fragments));
    }
    return new TemplateIndex(out);
  }

  private static List<Param> resolve(
      List<ParamDecl> decls, List<String> imports, ExpressionCompiler expressions, DiagnosticSink sink) {
    List<Param> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ParamDecl d : decls) {
      if (!seen.add(d.name())) {
        sink.error(d.pos(), "duplicate parameter '" + d.name() + "': rename or remove one of them");
        continue;
      }
      TypeMirror type = expressions.resolveType(d.type(), imports);
      if (type != null) {
        out.add(new Param(d.name(), type, type.toString()));
      }
    }
    return out;
  }

  /**
   * {@code Field} is a fragment parameter type only: its argument is a field path checked at the
   * call site, which a template rendered from a model has no way to supply.
   */
  private static List<Param> withoutFields(List<Param> params, List<ParamDecl> decls, DiagnosticSink sink) {
    List<Param> out = new ArrayList<>();
    for (Param p : params) {
      if (p.javaType().equals(FormPaths.FIELD)) {
        SourcePos pos = decls.stream().filter(d -> d.name().equals(p.name())).findFirst().map(ParamDecl::pos).orElseThrow();
        sink.error(
            pos,
            "parameter '" + p.name() + "' has type Field, which only fragment parameters may have: declare it in a"
                + " t:fragment signature");
      } else {
        out.add(p);
      }
    }
    return out;
  }

  private static void collectFragments(
      List<Node> nodes,
      List<String> imports,
      ExpressionParser parser,
      ExpressionCompiler expressions,
      DiagnosticSink sink,
      Map<String, FragmentInfo> out) {
    for (Node n : nodes) {
      if (!(n instanceof Element e)) {
        continue;
      }
      Attribute frag = e.attribute(Attribute.PREFIX + "fragment").orElse(null);
      if (frag != null && frag.value() == null) {
        sink.error(frag.pos(), "t:fragment needs a name, e.g. t:fragment=\"card(OwnerView owner)\"");
      } else if (frag != null) {
        FragmentSignature sig = parser.parseFragmentSignature(frag.value(), frag.valuePos(), sink);
        if (sig != null && out.containsKey(sig.name())) {
          sink.error(sig.pos(), "duplicate fragment '" + sig.name() + "' in this template: rename one of them");
        } else if (sig != null) {
          List<Param> params = resolve(sig.params(), imports, expressions, sink);
          out.put(sig.name(), new FragmentInfo(sig.name(), params, e, "fragment_" + sig.name()));
        }
      }
      collectFragments(e.children(), imports, parser, expressions, sink, out);
    }
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
