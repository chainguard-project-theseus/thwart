package dev.tessera.compiler.codegen;

import static dev.tessera.compiler.JavaStrings.literal;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.attr.CsrfInjector;
import dev.tessera.compiler.codegen.attr.FieldProcessor;
import dev.tessera.compiler.codegen.attr.FragmentMethods;
import dev.tessera.compiler.codegen.attr.TextInlining;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Comment;
import dev.tessera.compiler.model.DocType;
import dev.tessera.compiler.model.Document;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.model.OutputContext;
import dev.tessera.compiler.model.Text;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import dev.tessera.compiler.types.Variable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates the class for one template (shape in docs/mvp-design.md): walks the node tree,
 * emits static markup as string constants, builds an {@link ElementPlan} per element by running
 * {@link AttributeProcessors} in precedence order, and calls the per-template hooks
 * ({@code TextInlining} for text nodes, {@code CsrfInjector} for {@code <form>},
 * {@code FieldProcessor.option} for {@code <option>}, {@code FragmentMethods} once per template). Handles {@code t:params}/{@code t:import} on the
 * root element, {@code <t:block>} (tag omitted), and rejects unknown/misplaced core attributes.
 * Implements {@link CodegenContext}; {@link TemplateClassSource} assembles the class text.
 *
 * <p>Elements without {@code t:} attributes whose plan is unchanged are copied from the source
 * verbatim (start tag included), so static markup renders byte-identical. Rebuilt start tags
 * keep attribute order and quoting but normalize the whitespace between attributes to one space.
 *
 * <p>Not thread-safe: one instance generates one template at a time.
 */
public final class TemplateGenerator implements CodegenContext {
  private static final String RT = "dev.tessera.runtime.";
  /** Static text is split into constants of at most this many chars (constant-pool limit). */
  private static final int CHUNK = 8192;

  private final CompilerEnv env;
  private final TemplateIndex index;
  private final ExpressionParser parser;
  private final ExpressionCompiler expressions;
  private final Set<Element> selecting = Collections.newSetFromMap(new IdentityHashMap<>());

  // State for the template being generated.
  private TemplateIndex.TemplateInfo template;
  private String content;
  private int[] lineStarts;
  private Map<String, String> constants;
  private List<String> members;
  private int fresh;
  private Map<Element, String> whitespaceBefore;
  private Set<Element> htmxSubmits;

  public TemplateGenerator(
      CompilerEnv env, TemplateIndex index, ExpressionParser parser, ExpressionCompiler expressions) {
    this.env = env;
    this.index = index;
    this.parser = parser;
    this.expressions = expressions;
  }

  /**
   * Java name for a template parameter: always {@code name$}. The suffix keeps a parameter named
   * like a package root ({@code java}, {@code dev}, {@code com}) from obscuring the fully
   * qualified names in the generated body, and one named like a keyword or {@code ctx}/{@code out}
   * from clashing. Fragment parameters use the same rule.
   */
  public static String javaName(String templateVariable) {
    return templateVariable + "$";
  }

  /** Returns the generated Java source for {@code document}, even if errors were reported. */
  public List<GeneratedFile> generate(Document document) {
    TemplateIndex.TemplateInfo info =
        index
            .template(document.source().viewName())
            .orElseThrow(() -> new IllegalArgumentException("not indexed: " + document.source().viewName()));
    template = info;
    content = document.source().content();
    lineStarts = document.source().lineStarts();
    constants = new LinkedHashMap<>();
    members = new ArrayList<>();
    fresh = 0;
    whitespaceBefore = new IdentityHashMap<>();
    indexWhitespace(document.children());
    htmxSubmits = Collections.newSetFromMap(new IdentityHashMap<>());
    indexHtmxSubmits(document.children(), false, false);

    validate(document.rootElement(), document.children());
    Scope scope = Scope.empty();
    for (TemplateIndex.Param p : info.params()) {
      scope = scope.with(new Variable(p.name(), p.type(), javaName(p.name())));
    }
    Code body = new Code();
    for (Node n : document.children()) {
      node(body, n, scope);
    }
    String bodyCode = body.build();
    FragmentMethods.generate(this);
    return Backports.listOf(new GeneratedFile(GeneratedFile.Kind.JAVA, info.className(), TemplateClassSource.render(info, constants, members, bodyCode)));
  }

  // ---- CodegenContext ----

  @Override
  public CompilerEnv env() {
    return env;
  }

  @Override
  public TemplateIndex index() {
    return index;
  }

  @Override
  public TemplateIndex.TemplateInfo template() {
    return template;
  }

  @Override
  public ExpressionParser parser() {
    return parser;
  }

  @Override
  public ExpressionCompiler expressions() {
    return expressions;
  }

  @Override
  public Expr parse(Attribute attr) {
    if (attr.value() == null) {
      diagnostics().error(attr.pos(), attr.name() + " needs a value: write an expression, e.g. " + attr.name() + "=\"owner.name\"");
      return null;
    }
    return parser.parseExpression(attr.value(), attr.valuePos(), diagnostics());
  }

  @Override
  public CompiledExpr compile(Attribute attr, Scope scope) {
    Expr e = parse(attr);
    return e == null ? null : expressions.compile(e, scope);
  }

  @Override
  public String freshName(String hint) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < hint.length(); i++) {
      char c = hint.charAt(i);
      if (c < 128 && (Character.isLetterOrDigit(c) || c == '_')) {
        sb.append(c);
      }
    }
    if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
      sb.insert(0, 'v');
    }
    return sb.append('$').append(fresh++).toString();
  }

  @Override
  public String renderNode(Node node, Scope scope) {
    Code c = new Code();
    node(c, node, scope);
    return c.build();
  }

  @Override
  public String renderElement(Element element, Scope scope, Set<String> ignoredAttributes) {
    // Rendered standalone (fragment method, ~{::selector} helper): no preceding sibling text.
    String ws = whitespaceBefore.remove(element);
    try {
      Code c = new Code();
      element(c, element, scope, ignoredAttributes);
      return c.build();
    } finally {
      if (ws != null) {
        whitespaceBefore.put(element, ws);
      }
    }
  }

  @Override
  public void addMember(String javaCode) {
    members.add(javaCode);
  }

  @Override
  public String whitespaceBefore(Element element) {
    return whitespaceBefore.get(element);
  }

  @Override
  public boolean htmxSubmits(Element element) {
    return htmxSubmits.contains(element);
  }

  @Override
  public Set<Element> selecting() {
    return selecting;
  }

  /** Records {@link #whitespaceBefore} for every element preceded by a text sibling. */
  private void indexWhitespace(List<Node> nodes) {
    Node previous = null;
    for (Node n : nodes) {
      if (n instanceof Element) {
        Element e = (Element) n;
        if (previous instanceof Text) {
          String ws = trailingWhitespace(((Text) previous).raw());
          if (ws != null) {
            whitespaceBefore.put(e, ws);
          }
        }
        indexWhitespace(e.children());
      }
      previous = n;
    }
  }

  /**
   * Records {@link #htmxSubmits}. {@code boosted}: the nearest static {@code hx-boost} above is not
   * {@code false} (htmx inherits it; {@code hx-disinherit} is ignored, which only adds checks).
   * {@code htmxForm}: inside a {@code <form>} that is boosted or has an htmx request attribute.
   * An {@code hx-boost="false"} whose tag is not rendered ({@code <t:block>}, {@code t:remove="tag"})
   * opts nothing out, and an inner form keeps {@code htmxForm}: browsers drop a nested {@code <form>}.
   */
  private void indexHtmxSubmits(List<Node> nodes, boolean boosted, boolean htmxForm) {
    for (Node n : nodes) {
      if (!(n instanceof Element)) {
        continue;
      }
      Element e = (Element) n;
      Boolean boost = Contexts.staticHtmxBoost(e);
      if (Boolean.FALSE.equals(boost)
          && (e.name().equals("t:block")
              || e.attribute("t:remove").map(Attribute::value).map(String::trim).filter("tag"::equals).isPresent())) {
        boost = null;
      }
      boolean b = boost == null ? boosted : boost;
      boolean form = e.name().equalsIgnoreCase("form");
      boolean f = htmxForm || (form && (b || Contexts.hasHtmxRequestAttribute(e)));
      // A form's own action is requested only when it is boosted; with hx-post etc. htmx ignores it.
      if (form ? b : b || f) {
        htmxSubmits.add(e);
      }
      indexHtmxSubmits(e.children(), b, f);
    }
  }

  /**
   * Thymeleaf splits text at inlined expressions and repeats the last piece if it is all
   * whitespace: the whole text if it is whitespace, else the whitespace after a closing
   * {@code ]]} or {@code )]} of an inlined expression. Null otherwise.
   */
  private static String trailingWhitespace(String raw) {
    int start = raw.length();
    while (start > 0 && Character.isWhitespace(raw.charAt(start - 1))) {
      start--;
    }
    if (start == raw.length()) {
      return null;
    }
    if (start == 0) {
      return raw;
    }
    String head = raw.substring(0, start);
    boolean inlined =
        (head.endsWith("]]") && head.contains("[[")) || (head.endsWith(")]") && head.contains("[("));
    return inlined ? raw.substring(start) : null;
  }

  // ---- tree walk ----

  /** Reports misplaced core attributes and exclusive attribute pairs, once per element. */
  private void validate(Element root, List<Node> nodes) {
    for (Node n : nodes) {
      if (!(n instanceof Element)) {
        continue;
      }
      Element e = (Element) n;
      for (Attribute a : e.tesseraAttributes()) {
        if (AttributeProcessors.CORE.contains(a.localName()) && e != root) {
          diagnostics()
              .error(a.pos(), a.name() + " is only allowed on the root element (the first element of the template)");
        }
      }
      exclusive(e, "text", "utext");
      exclusive(e, "insert", "replace");
      validate(root, e.children());
    }
  }

  private void exclusive(Element e, String a, String b) {
    Attribute first = e.attribute(Attribute.PREFIX + a).orElse(null);
    Attribute second = e.attribute(Attribute.PREFIX + b).orElse(null);
    if (first != null && second != null) {
      diagnostics()
          .error(second.pos(), "t:" + a + " and t:" + b + " cannot be used on the same element; keep one of them");
    }
  }

  private void node(Code c, Node n, Scope scope) {
    if (n instanceof Text) {
      Text t = (Text) n;
      String code = TextInlining.render(this, t, scope);
      if (code == null) {
        c.text(t.raw());
      } else {
        c.stmt(code);
      }
    } else if (n instanceof Comment) {
      c.text(((Comment) n).raw());
    } else if (n instanceof DocType) {
      c.text(((DocType) n).raw());
    } else {
      element(c, (Element) n, scope, Collections.emptySet());
    }
  }

  private void element(Code c, Element e, Scope scope, Set<String> ignored) {
    ElementPlan plan = new ElementPlan(e, scope);
    if (e.name().equals("t:block")) {
      plan.omitTag();
    }
    List<Attribute> attrs = new ArrayList<>();
    for (Attribute a : e.tesseraAttributes()) {
      if (!AttributeProcessors.CORE.contains(a.localName()) && !ignored.contains(a.localName())) {
        attrs.add(a);
      }
    }
    attrs.sort(Comparator.comparingInt(a -> AttributeProcessors.precedence(a.localName())));
    for (Attribute a : attrs) {
      // A removed element renders nothing: later attributes are not compiled here (a
      // parameterized t:fragment's attributes only make sense in its fragment method).
      if (plan.removed()) {
        break;
      }
      AttributeProcessors.forName(a.localName()).ifPresent(p -> p.process(this, a, plan));
    }
    if (e.name().equalsIgnoreCase("form")) {
      CsrfInjector.apply(this, plan);
    }
    if (e.name().equalsIgnoreCase("option")) {
      FieldProcessor.option(this, plan);
    }
    emit(c, plan);
  }

  private void emit(Code c, ElementPlan plan) {
    Element e = plan.element();
    List<ElementPlan.Wrapper> wrappers = plan.wrappers();
    for (ElementPlan.Wrapper w : wrappers) {
      c.stmt(w.open());
    }
    if (!plan.removed()) {
      if (plan.replacement() != null) {
        c.stmt(plan.replacement());
      } else {
        if (!plan.tagOmitted()) {
          startTag(c, plan);
        }
        for (String s : plan.afterStartTagStatements()) {
          c.stmt(s);
        }
        if (plan.body() != null) {
          c.stmt(plan.body());
        } else {
          for (Node n : e.children()) {
            node(c, n, plan.scope());
          }
        }
        if (!plan.tagOmitted() && (e.hasEndTag() || opensSelfClosing(plan))) {
          c.text("</" + e.name() + ">");
        }
      }
    }
    for (int i = wrappers.size() - 1; i >= 0; i--) {
      c.stmt(wrappers.get(i).close());
    }
  }

  private void startTag(Code c, ElementPlan plan) {
    Element e = plan.element();
    String source = sourceStartTag(e);
    if (source != null && unchanged(e, plan)) {
      c.text(source);
      return;
    }
    c.text("<" + e.name());
    for (ElementPlan.Attr a : plan.attributes()) {
      if (a instanceof ElementPlan.StaticAttr) {
        c.text(" " + a.name() + ((ElementPlan.StaticAttr) a).source().sourceValue());
      } else {
        c.stmt(dynamicAttr((ElementPlan.DynamicAttr) a));
      }
    }
    if (!e.selfClosing() || opensSelfClosing(plan)) {
      c.text(">");
    } else if (source != null && source.length() > 2 && Character.isWhitespace(source.charAt(source.length() - 3))) {
      c.text(" />");
    } else {
      c.text("/>");
    }
  }

  /**
   * A self-closing element whose body a processor replaced ({@code <td t:text="x"/>}) renders as
   * {@code <td>x</td>}, like Thymeleaf; otherwise the text would follow a {@code <td/>} tag.
   */
  private static boolean opensSelfClosing(ElementPlan plan) {
    return plan.element().selfClosing() && plan.body() != null;
  }

  /** True if the start tag can be copied from the source: no t: attributes and nothing changed. */
  private static boolean unchanged(Element e, ElementPlan plan) {
    if (!e.tesseraAttributes().isEmpty() || e.attribute("xmlns:t").isPresent()) {
      return false;
    }
    List<ElementPlan.Attr> attrs = plan.attributes();
    if (attrs.size() != e.attributes().size()) {
      return false;
    }
    for (int i = 0; i < attrs.size(); i++) {
      if (!(attrs.get(i) instanceof ElementPlan.StaticAttr)
          || ((ElementPlan.StaticAttr) attrs.get(i)).source() != e.attributes().get(i)) {
        return false;
      }
    }
    return true;
  }

  private String dynamicAttr(ElementPlan.DynamicAttr d) {
    // Only attribute contexts: e.g. the SCRIPT (json) escaper does not escape '"'. A STYLE
    // attribute is attribute-escaped; GenericAttrProcessor admits only safe style values (#182).
    OutputContext ctx = d.context();
    String escaper =
        ctx == OutputContext.ATTRIBUTE || ctx == OutputContext.URL || ctx == OutputContext.RESOURCE_URL
            || ctx == OutputContext.REFRESH || ctx == OutputContext.SCRIPT_ATTRIBUTE
            ? ctx.escaper()
            : ctx == OutputContext.STYLE ? OutputContext.ATTRIBUTE.escaper() : null;
    if (escaper == null) {
      diagnostics().error(d.pos(), "an expression cannot be used in attribute " + d.name() + " (" + d.context()
              + " context): write a literal value");
      return "";
    }
    String v = freshName("v");
    // A JSON value (SCRIPT_ATTRIBUTE, #246) is written even when null, as JSON null.
    return "{ java.lang.Object "
        + v
        + " = "
        + d.valueCode()
        + "; if ("
        + (ctx == OutputContext.SCRIPT_ATTRIBUTE ? "true" : v + " != null")
        + ") { out.write("
        + literal(" " + d.name() + "=\"")
        + "); "
        + RT
        + "Escapers."
        + escaper
        + "(out, "
        + v
        + "); out.write('\"'); } }";
  }

  /**
   * The element's start tag exactly as written in the source, or null if it cannot be located
   * (positions inconsistent with the content).
   */
  private String sourceStartTag(Element e) {
    SourcePos pos = e.pos();
    if (pos == null || pos.line() < 1 || pos.line() > lineStarts.length || pos.column() < 1) {
      return null;
    }
    int start = lineStarts[pos.line() - 1] + pos.column() - 1;
    if (start >= content.length() || !content.startsWith("<" + e.name(), start)) {
      return null;
    }
    char quote = 0;
    for (int i = start + 1 + e.name().length(); i < content.length(); i++) {
      char ch = content.charAt(i);
      if (quote != 0) {
        if (ch == quote) {
          quote = 0;
        }
      } else if (ch == '"' || ch == '\'') {
        quote = ch;
      } else if (ch == '>') {
        String tag = content.substring(start, i + 1);
        return tag.endsWith("/>") == e.selfClosing() ? tag : null;
      }
    }
    return null;
  }

  /**
   * Name of the constant holding {@code text}. The {@code $} keeps it distinct from every
   * template variable (template identifiers cannot contain {@code $}), so a param or fragment
   * param can never shadow a constant and get written unescaped in its place.
   */
  private String constant(String text) {
    return constants.computeIfAbsent(text, k -> "S$" + constants.size());
  }

  /** Accumulates statements, coalescing adjacent static text into constant writes. */
  private final class Code {
    private final StringBuilder code = new StringBuilder();
    private final StringBuilder text = new StringBuilder();

    void text(String s) {
      text.append(s);
    }

    void stmt(String s) {
      flush();
      if (!s.isEmpty()) {
        code.append(s);
        if (!s.endsWith("\n")) {
          code.append('\n');
        }
      }
    }

    String build() {
      flush();
      return code.toString();
    }

    private void flush() {
      int i = 0;
      while (i < text.length()) {
        int end = Math.min(text.length(), i + CHUNK);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
          end--;
        }
        code.append("out.write(").append(constant(text.substring(i, end))).append(");\n");
        i = end;
      }
      text.setLength(0);
    }
  }
}
