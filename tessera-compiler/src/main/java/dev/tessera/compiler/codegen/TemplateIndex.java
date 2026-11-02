package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.expr.FragmentSignature;
import dev.tessera.compiler.expr.ParamDecl;
import dev.tessera.compiler.forms.FormPaths;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.html.ParsingContexts;
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
import java.util.Map;
import java.util.Objects;
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
    this.templates = Backports.listCopy(templates);
  }

  /** A resolved parameter. {@code javaType} is source text usable in generated code. */
  public static final class Param {
    private final String name;
    private final TypeMirror type;
    private final String javaType;

    public Param(String name, TypeMirror type, String javaType) {
      this.name = name;
      this.type = type;
      this.javaType = javaType;
    }

    public String name() {
      return name;
    }

    public TypeMirror type() {
      return type;
    }

    public String javaType() {
      return javaType;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Param)) {
        return false;
      }
      Param that = (Param) o;
      return Objects.equals(name, that.name)
          && Objects.equals(type, that.type)
          && Objects.equals(javaType, that.javaType);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(type);
      h = 31 * h + Objects.hashCode(javaType);
      return h;
    }

    @Override
    public String toString() {
      return "Param[name=" + name + ", type=" + type + ", javaType=" + javaType + "]";
    }
  }

  /**
   * A {@code t:fragment} definition.
   *
   * @param methodName static method on the template class, {@code fragment_<name>}
   */
  public static final class FragmentInfo {
    private final String name;
    private final List<Param> params;
    private final Element element;
    private final String methodName;

    public FragmentInfo(String name, List<Param> params, Element element, String methodName) {
      this.name = name;
      this.params = Backports.listCopy(params);
      this.element = element;
      this.methodName = methodName;
    }

    public String name() {
      return name;
    }

    public List<Param> params() {
      return params;
    }

    public Element element() {
      return element;
    }

    public String methodName() {
      return methodName;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof FragmentInfo)) {
        return false;
      }
      FragmentInfo that = (FragmentInfo) o;
      return Objects.equals(name, that.name)
          && Objects.equals(params, that.params)
          && Objects.equals(element, that.element)
          && Objects.equals(methodName, that.methodName);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(name);
      h = 31 * h + Objects.hashCode(params);
      h = 31 * h + Objects.hashCode(element);
      h = 31 * h + Objects.hashCode(methodName);
      return h;
    }

    @Override
    public String toString() {
      return "FragmentInfo[name=" + name + ", params=" + params + ", element=" + element
          + ", methodName=" + methodName + "]";
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
  public static final class TemplateInfo {
    private final Document document;
    private final String className;
    private final List<String> imports;
    private final List<Param> params;
    private final Map<String, FragmentInfo> fragments;
    /** Computed on first use; not part of equals. */
    private ParsingContexts parsingContexts;

    public TemplateInfo(
        Document document,
        String className,
        List<String> imports,
        List<Param> params,
        Map<String, FragmentInfo> fragments) {
      this.document = document;
      this.className = className;
      this.imports = Backports.listCopy(imports);
      this.params = Backports.listCopy(params);
      this.fragments = Backports.mapCopy(fragments);
    }

    public Document document() {
      return document;
    }

    public String className() {
      return className;
    }

    public List<String> imports() {
      return imports;
    }

    public List<Param> params() {
      return params;
    }

    public Map<String, FragmentInfo> fragments() {
      return fragments;
    }

    /** How a browser parses each element of {@link #document} (computed once). */
    public ParsingContexts parsingContexts() {
      if (parsingContexts == null) {
        parsingContexts = ParsingContexts.of(document);
      }
      return parsingContexts;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof TemplateInfo)) {
        return false;
      }
      TemplateInfo that = (TemplateInfo) o;
      return Objects.equals(document, that.document)
          && Objects.equals(className, that.className)
          && Objects.equals(imports, that.imports)
          && Objects.equals(params, that.params)
          && Objects.equals(fragments, that.fragments);
    }

    @Override
    public int hashCode() {
      int h = Objects.hashCode(document);
      h = 31 * h + Objects.hashCode(className);
      h = 31 * h + Objects.hashCode(imports);
      h = 31 * h + Objects.hashCode(params);
      h = 31 * h + Objects.hashCode(fragments);
      return h;
    }

    @Override
    public String toString() {
      return "TemplateInfo[document=" + document + ", className=" + className + ", imports=" + imports
          + ", params=" + params + ", fragments=" + fragments + "]";
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
      String previous = classOwners.putIfAbsent(HtmlParser.asciiLower(className), doc.source().path());
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
      List<String> imports = Backports.listOf();
      List<Param> params = Backports.listOf();
      if (root != null) {
        Attribute imp = root.attribute(Attribute.PREFIX + "import").orElse(null);
        if (imp != null && imp.value() == null) {
          sink.error(imp.pos(), imp.name() + " needs a value, e.g. t:import=\"java.util.List\"");
        } else if (imp != null) {
          List<String> parsed = parser.parseImports(imp.value(), imp.valuePos(), sink);
          imports = parsed == null ? Backports.listOf() : parsed;
        }
        Attribute par = root.attribute(Attribute.PREFIX + "params").orElse(null);
        if (par != null && par.value() == null) {
          sink.error(par.pos(), par.name() + " needs a value, e.g. t:params=\"String name, int count\"");
        } else if (par != null) {
          List<ParamDecl> decls = parser.parseParams(par.value(), par.valuePos(), sink);
          params = resolve(decls == null ? Backports.listOf() : decls, imports, expressions, sink);
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
        out.add(new Param(d.name(), type, isFragment(type) ? JavaStrings.FRAGMENT_BODY : type.toString()));
      }
    }
    return out;
  }

  /**
   * Whether parameter type {@code t} is {@code Fragment}. {@code Fragment} is final, so a parameter
   * that can take one is exactly that type.
   */
  public static boolean isFragment(TypeMirror t) {
    return ExpressionCompiler.isDeclared(t, dev.tessera.runtime.Fragment.class.getName());
  }

  /**
   * {@code Field} is a fragment parameter type only: its argument is a field path checked at the
   * call site, which a template rendered from a model has no way to supply. So is {@code Fragment}
   * (#676): no Java code can build one, so only a template calling the fragment can pass it.
   */
  private static List<Param> withoutFields(List<Param> params, List<ParamDecl> decls, DiagnosticSink sink) {
    List<Param> out = new ArrayList<>();
    for (Param p : params) {
      boolean fragment = p.javaType().equals(JavaStrings.FRAGMENT_BODY);
      if (FormPaths.isField(p.type()) || fragment) {
        SourcePos pos = decls.stream().filter(d -> d.name().equals(p.name())).findFirst().map(ParamDecl::pos).get();
        sink.error(
            pos,
            "parameter '" + p.name() + "' has type " + (fragment ? "Fragment" : "Field") + ", which only fragment"
                + " parameters may have: declare it in a t:fragment signature"
                + (fragment ? " and pass it ~{...} from the template that calls it (Java can't build a Fragment, #676)" : ""));
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
      if (!(n instanceof Element)) {
        continue;
      }
      Element e = (Element) n;
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
