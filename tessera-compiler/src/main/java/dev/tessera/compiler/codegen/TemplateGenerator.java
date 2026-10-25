package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.attr.CsrfInjector;
import dev.tessera.compiler.codegen.attr.FieldProcessor;
import dev.tessera.compiler.codegen.attr.FragmentMethods;
import dev.tessera.compiler.codegen.attr.FragmentTop;
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
import dev.tessera.compiler.verify.Skeleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
  /** Static text is split into constants of at most this many chars (constant-pool limit). */
  private static final int CHUNK = 8192;

  /** {@code t:} attributes that write HTML known only at runtime into the element. */
  private static final Set<String> RUNTIME_HTML = Backports.setOf("utext", "insert", "replace");
  /** Elements whose content a browser reads in a table insertion mode (#348); see {@link #tableHost}. */
  private static final Set<String> TABLE_HOSTS = Backports.setOf("table", "tbody", "thead", "tfoot", "tr", "colgroup");
  /** Attributes that write computed text as the element's content. */
  private static final Set<String> TEXT_BODY = Backports.setOf("text", "utext", "errors");
  /** Elements a browser places by the table rules (#348); see {@link #tablePartFits}. */
  private static final Set<String> TABLE_PARTS = FragmentTop.TABLE_PARTS;
  /** {@link #tableCtx} of an element rendered standalone (a fragment): where it lands is checked there. */
  private static final String SOMEWHERE = "?";

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
  /** The skeleton of the template generated last (#366). */
  private Skeleton.TemplateSkeleton skeleton;
  /** The fragment whose method is being generated, or null. */
  private TemplateIndex.FragmentInfo fragment;
  /**
   * The lower-case name of the nearest element being generated whose tag is written, or null:
   * the element of a text hole ({@link #hostElement}).
   */
  private String hostElement;
  private int fresh;
  private Map<Element, String> whitespaceBefore;
  private Set<Element> htmxSubmits;
  /**
   * The element a browser reads as raw text or RCDATA whose content is being generated, or null.
   * See {@link #rawTextElement}.
   */
  private String rawTextHost;
  /** Whether the content being generated is inside an {@code <svg>} or {@code <math>} element. */
  private boolean inForeign;
  /**
   * The {@code <form>}s around the element being rendered (#314): null outside any, TRUE if one of
   * them may get the CSRF token or the element is rendered standalone, FALSE if none does.
   */
  private Boolean csrfForm;
  /**
   * The table element ({@link #TABLE_HOSTS}) whose direct content is being generated, or null
   * (#348). A browser moves text out of it in front of the table but keeps comments in place, so
   * the empty comments that break {@code {{} in a value are left behind and the braces join up
   * again. {@code <t:block>} and {@code t:remove="tag"} are transparent. Checked in any namespace:
   * {@code <table>} ends {@code <svg>} and {@code <math>}, and no SVG or MathML element has these names.
   */
  private String tableHost;
  /**
   * The lower-case name of the nearest enclosing {@link #TABLE_PARTS} element, null if there is
   * none, or {@link #SOMEWHERE} in an element rendered standalone (#348).
   */
  private String tableCtx;
  /** Whether the content being generated is directly in {@link #tableCtx}: only tagless elements in between. */
  private boolean tableDirect;

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
    csrfForm = null;
    whitespaceBefore = new IdentityHashMap<>();
    indexWhitespace(document.children());
    htmxSubmits = Collections.newSetFromMap(new IdentityHashMap<>());
    indexHtmxSubmits(document.children(), false, false);

    validate(document.rootElement(), document.children());
    Scope scope = Scope.empty();
    for (TemplateIndex.Param p : info.params()) {
      scope = scope.with(new Variable(p.name(), p.type(), javaName(p.name())));
    }
    hostElement = null;
    fragment = null;
    Emitted.Builder body = builder();
    for (Node n : document.children()) {
      node(body, n, scope);
    }
    Emitted bodyCode = body.build();
    Map<String, Skeleton.Seq> fragments = FragmentMethods.generate(this);
    skeleton = new Skeleton.TemplateSkeleton(info.viewName(), bodyCode.seq(), fragments);
    return Backports.listOf(new GeneratedFile(GeneratedFile.Kind.JAVA, info.className(), TemplateClassSource.render(info, constants, members, bodyCode.java())));
  }

  /**
   * The skeleton of the template {@link #generate} generated last: exactly what its class writes
   * (#366). Meaningful only if no errors were reported.
   */
  public Skeleton.TemplateSkeleton skeleton() {
    return skeleton;
  }

  private Emitted.Builder builder() {
    return new Emitted.Builder(this::constant, CHUNK);
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
  public Emitted renderNode(Node node, Scope scope) {
    Emitted.Builder c = builder();
    node(c, node, scope);
    return c.build();
  }

  @Override
  public Emitted renderFragment(TemplateIndex.FragmentInfo f, Scope scope) {
    TemplateIndex.FragmentInfo outer = fragment;
    fragment = f;
    try {
      return renderElement(f.element(), scope, Backports.setOf("fragment"));
    } finally {
      fragment = outer;
    }
  }

  @Override
  public TemplateIndex.FragmentInfo fragment() {
    return fragment;
  }

  @Override
  public String hostElement() {
    return hostElement;
  }

  @Override
  public Emitted renderElement(Element element, Scope scope, Set<String> ignoredAttributes) {
    // Rendered standalone (fragment method, ~{::selector} helper): no preceding sibling text.
    String ws = whitespaceBefore.remove(element);
    // ... and rendered where it is inserted, which is checked there, not inside its source parent.
    String host = rawTextHost;
    boolean foreign = inForeign;
    String table = tableHost;
    String tablePart = tableCtx;
    boolean direct = tableDirect;
    String outerHost = hostElement;
    rawTextHost = null;
    inForeign = false;
    hostElement = null;
    // ... and may be inserted into a POST form that gets the CSRF token, where a browser drops the
    // start tag of a <form> inside it, so its buttons submit that form (#314).
    Boolean form = csrfForm;
    csrfForm = Boolean.TRUE;
    tableHost = null;
    tableCtx = SOMEWHERE;
    tableDirect = true;
    try {
      Emitted.Builder c = builder();
      element(c, element, scope, ignoredAttributes);
      return c.build();
    } finally {
      hostElement = outerHost;
      rawTextHost = host;
      inForeign = foreign;
      csrfForm = form;
      tableHost = table;
      tableCtx = tablePart;
      tableDirect = direct;
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
  public boolean mayPostCsrfToken(Element element) {
    return !Boolean.FALSE.equals(csrfForm) || CsrfInjector.hasFormAttribute(element);
  }

  @Override
  public Set<Element> selecting() {
    return selecting;
  }

  @Override
  public String insertionContext() {
    return inForeign || rawTextHost != null ? "ctx.withCspNonce(null)" : "ctx";
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

  private void node(Emitted.Builder c, Node n, Scope scope) {
    if (n instanceof Text) {
      Text t = (Text) n;
      if (rawTextHost != null
          && (t.context() == OutputContext.TEXT || t.context() == OutputContext.RCDATA)) {
        int unescaped = TextInlining.firstUnescaped(t.raw());
        if (unescaped >= 0) {
          runtimeHtmlInRawText(t.pos().advance(t.raw(), unescaped), "[(...)]", rawTextHost);
        }
      }
      int hole = tableHost == null ? -1 : TextInlining.firstHole(t.raw());
      if (hole >= 0) {
        textInTable(t.pos().advance(t.raw(), hole + 2), t.raw().startsWith("[[", hole) ? "[[...]]" : "[(...)]");
      }
      Emitted code = TextInlining.render(this, t, scope);
      if (code == null) {
        c.text(t.raw(), t.pos());
      } else {
        c.add(code);
      }
    } else if (n instanceof Comment) {
      c.text(((Comment) n).raw(), ((Comment) n).pos());
    } else if (n instanceof DocType) {
      c.text(((DocType) n).raw(), ((DocType) n).pos());
    } else {
      element(c, (Element) n, scope, Collections.emptySet());
    }
  }

  private void element(Emitted.Builder c, Element e, Scope scope, Set<String> ignored) {
    String outer = rawTextHost;
    String outerHost = hostElement;
    boolean outerForeign = inForeign;
    Boolean outerForm = csrfForm;
    String outerTable = tableHost;
    String outerCtx = tableCtx;
    boolean outerDirect = tableDirect;
    String host = rawTextElement(e) ? e.name() : outer;
    // Processors (t:remove="all-but-first") and emit generate the content.
    rawTextHost = host;
    inForeign = outerForeign || e.name().equalsIgnoreCase("svg") || e.name().equalsIgnoreCase("math");
    if (!FragmentTop.tagNotRendered(e)) {
      String lower = e.name().toLowerCase(Locale.ROOT);
      hostElement = lower;
      // A t:replace'd element is not written: what replaces it is checked below.
      if (!e.attribute("t:replace").isPresent()) {
        if (TABLE_PARTS.contains(lower) && !tablePartFits(lower, tableCtx, tableDirect)) {
          misplacedTablePart(e.pos(), "<" + e.name() + ">", tableCtx);
        } else if (lower.equals("form") && tableHost != null) {
          misplacedTablePart(e.pos(), "<" + e.name() + ">", tableCtx);
        }
      }
      tableHost = TABLE_HOSTS.contains(lower) ? e.name() : null;
      tableDirect = TABLE_PARTS.contains(lower);
      if (tableDirect) {
        tableCtx = lower;
      }
    }
    try {
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
        // t:replace swaps the whole element: on a raw text element itself it writes nothing inside it.
        String within = a.localName().equals("replace") ? outer : host;
        if (within != null && RUNTIME_HTML.contains(a.localName())) {
          runtimeHtmlInRawText(a.pos(), a.name(), within);
        }
        if (tableHost != null && TEXT_BODY.contains(a.localName())) {
          textInTable(a.pos(), a.name());
        }
        if (a.localName().equals("insert")) {
          insertedInTable(a, tableCtx, tableDirect);
        } else if (a.localName().equals("replace")) {
          insertedInTable(a, outerCtx, outerDirect);
        }
        AttributeProcessors.forName(a.localName()).ifPresent(p -> p.process(this, a, plan));
      }
      if (e.name().equalsIgnoreCase("form")) {
        boolean token = CsrfInjector.apply(this, plan);
        if (!plan.tagOmitted()) {
          // A browser drops a nested form's start tag: the inner one's buttons submit the outer form.
          csrfForm = Boolean.TRUE.equals(outerForm) || token;
        }
      }
      if (!plan.removed() && plan.replacement() == null) {
        CsrfInjector.checkSubmitter(this, plan);
      }
      if (e.name().equalsIgnoreCase("base")) {
        CsrfInjector.checkBase(this, e);
      }
      if (e.name().equalsIgnoreCase("option")) {
        FieldProcessor.option(this, plan);
      }
      emit(c, plan);
    } finally {
      hostElement = outerHost;
      rawTextHost = outer;
      inForeign = outerForeign;
      csrfForm = outerForm;
      tableHost = outerTable;
      tableCtx = outerCtx;
      tableDirect = outerDirect;
    }
  }

  /**
   * Whether a browser keeps the table part {@code lower} where it is written, given the nearest
   * enclosing table part {@code ctx} and whether it is directly in it (#348). Anywhere else the
   * browser closes elements up to a table part that takes it (a {@code <tr>} in a {@code <td>}
   * ends the cell), so the content after it would be read in a table insertion mode, which moves
   * text in front of the table. Outside tables a browser ignores the tag but keeps the content.
   */
  private static boolean tablePartFits(String lower, String ctx, boolean direct) {
    // A <table> in a cell nests; elsewhere in a table it ends that table, so what follows is not in a table mode.
    if (ctx == null || lower.equals("table")) {
      return true;
    }
    if (ctx.equals(SOMEWHERE)) {
      return direct;
    }
    if (!direct) {
      return false;
    }
    switch (lower) {
      case "tr":
        return ctx.equals("table") || ctx.equals("tbody") || ctx.equals("thead") || ctx.equals("tfoot");
      case "td":
      case "th":
        return ctx.equals("table") || ctx.equals("tbody") || ctx.equals("thead") || ctx.equals("tfoot") || ctx.equals("tr");
      case "col":
        return ctx.equals("table") || ctx.equals("colgroup");
      default:
        return ctx.equals("table");
    }
  }

  /**
   * Checks what {@code attr} ({@code t:insert} or {@code t:replace}) writes where it lands: in
   * table part {@code ctx}, directly or not (#348). At the top of a fragment the fragment's own
   * call sites check it.
   */
  private void insertedInTable(Attribute attr, String ctx, boolean direct) {
    if (ctx == null || (ctx.equals(SOMEWHERE) && direct)) {
      return;
    }
    FragmentTop top = FragmentTop.of(this, attr);
    if (top.unknown) {
      diagnostics()
          .error(attr.pos(), attr.name() + " inside a table must insert a fragment Tessera can follow (~{template :: name})");
      return;
    }
    boolean host = direct && TABLE_HOSTS.contains(ctx);
    if (host && top.text) {
      diagnostics()
          .error(attr.pos(), attr.name() + " writes computed text or a Fragment value directly inside <" + ctx + ">, which"
              + " is not supported: a browser moves the text in front of the table but leaves the comments that break {{"
              + " in the value inside it, so Vue or AngularJS could run the value as code; put the value in a <td>, <th>"
              + " or <caption>, or insert a fragment whose top element is a table row or cell");
    }
    for (String n : top.elements) {
      if (TABLE_PARTS.contains(n) ? !tablePartFits(n, ctx, direct) : host && n.equals("form")) {
        misplacedTablePart(attr.pos(), attr.name() + " of a fragment that writes <" + n + ">", ctx);
      }
    }
  }

  /** Reports a table part or form written where a browser would not keep it (#348). */
  private void misplacedTablePart(SourcePos pos, String what, String ctx) {
    String where = ctx == null || ctx.equals(SOMEWHERE) ? "here" : "inside <" + ctx + ">";
    diagnostics()
        .error(pos, what + " " + where + " is not supported: a browser would close elements to place it (a <tr> ends"
            + " a <td>, a <form> in a table is closed at once), and then move text after it in front of the table,"
            + " leaving the comments that break {{ in a value behind, so Vue or AngularJS could run the value as code;"
            + " write table parts only directly in the part that takes them (<tr> in <tbody>, <td> in <tr>)");
  }

  /** Reports computed text written directly inside {@link #tableHost}. */
  private void textInTable(SourcePos pos, String what) {
    diagnostics()
        .error(pos, what + " directly inside <" + tableHost + "> is not supported: a browser moves the text in front of"
            + " the table but leaves the comments that break {{ in the value inside it, so Vue or AngularJS could run"
            + " the value as code; put the value in a <td>, <th> or <caption>");
  }

  /**
   * Whether a browser reads the content of {@code e} as raw text or RCDATA up to the first
   * {@code </name}: {@link Contexts#browserRawText} elements, {@code <textarea>} and {@code <title>}.
   * Tessera treats {@code <title>} in {@code <svg>} and {@code <math>} as RCDATA too: a browser
   * reads it as RCDATA in an HTML integration point ({@code <foreignObject>}, {@code <mtext>}) or
   * after a tag that ends foreign content ({@code <b>}).
   */
  private static boolean rawTextElement(Element e) {
    String lower = e.name().toLowerCase(Locale.ROOT);
    return Contexts.browserRawText(lower) || lower.equals("textarea") || lower.equals("title");
  }

  /**
   * HTML known only at runtime (a {@code SafeHtml}, a rendered fragment) could contain {@code
   * </name}, which ends a raw text element early in a browser and makes the rest of its content
   * live markup, so it cannot be written inside one.
   */
  private void runtimeHtmlInRawText(SourcePos pos, String what, String element) {
    diagnostics()
        .error(pos, what + " inside <" + element + "> is not supported: a browser reads the content of <" + element
            + "> as text up to the first '</" + element + "', and inserted markup could contain one; move it"
            + " outside the element");
  }

  private void emit(Emitted.Builder c, ElementPlan plan) {
    Element e = plan.element();
    List<ElementPlan.Wrapper> wrappers = plan.wrappers();
    for (ElementPlan.Wrapper w : wrappers) {
      c.open(w);
    }
    if (!plan.removed()) {
      if (plan.replacement() != null) {
        c.add(plan.replacement());
      } else {
        if (!plan.tagOmitted() && e.selfClosing() && rawTextElement(e) && !opensSelfClosing(plan)) {
          selfClosingRawText(e);
        }
        if (!plan.tagOmitted()) {
          startTag(c, plan);
        }
        for (Emitted s : plan.afterStartTagStatements()) {
          c.add(s);
        }
        if (plan.body() != null) {
          c.add(plan.body());
        } else {
          for (Node n : e.children()) {
            node(c, n, plan.scope());
          }
        }
        if (!plan.tagOmitted() && (e.hasEndTag() || opensSelfClosing(plan))) {
          c.text("</" + e.name() + ">", e.pos());
        }
      }
    }
    for (int i = wrappers.size() - 1; i >= 0; i--) {
      c.close(wrappers.get(i));
    }
  }

  /**
   * Reports {@code <textarea/>} and the like written as is: a browser ignores the '/' and reads
   * the rest of the page as the element's text up to the first {@code </name}, which Tessera may
   * have read inside a comment or attribute value.
   */
  private void selfClosingRawText(Element e) {
    String lower = e.name().toLowerCase(Locale.ROOT);
    if (inForeign && lower.equals("title")) {
      diagnostics()
          .error(e.pos(), "<title/> is not supported inside <svg> or <math>: Tessera cannot tell an SVG title"
              + " from an HTML one (in <foreignObject> or after <b>), after which a browser reads the rest of the"
              + " page as title text; write <title></title>");
      return;
    }
    diagnostics()
        .error(e.pos(), "<" + lower + "/> is not self-closing in HTML: the browser treats everything after it as "
            + lower + "; write <" + lower + " ...></" + lower + ">");
  }

  private void startTag(Emitted.Builder c, ElementPlan plan) {
    Element e = plan.element();
    boolean nonce = takesCspNonce(plan);
    String source = sourceStartTag(e);
    if (source != null && !nonce && unchanged(e, plan)) {
      c.text(source, e.pos());
      return;
    }
    c.text("<" + e.name(), e.pos());
    for (ElementPlan.Attr a : plan.attributes()) {
      if (a instanceof ElementPlan.StaticAttr) {
        Attribute source1 = ((ElementPlan.StaticAttr) a).source();
        c.text(" " + a.name() + source1.sourceValue(), source1.pos());
      } else {
        c.add(dynamicAttr(e, (ElementPlan.DynamicAttr) a));
      }
    }
    if (nonce) {
      String n = freshName("nonce");
      c.add(
          Emitted.opt(
              Skeleton.OptKind.CSP_NONCE,
              e.pos(),
              "{ java.util.Optional<java.lang.String> " + n + " = ctx.cspNonce(); if (" + n + ".isPresent()) { ",
              Emitted.concat(
                  Emitted.text(" nonce=\"", null),
                  Emitted.code(" "),
                  Emitted.hole(Skeleton.Escaper.ATTR, e.name(), "nonce", e.pos(), n + ".get()"),
                  Emitted.code(" "),
                  Emitted.text('"', null)),
              " } }"));
    }
    if (!e.selfClosing() || opensSelfClosing(plan)) {
      c.text(">", e.pos());
    } else if (source != null && source.length() > 2 && Character.isWhitespace(source.charAt(source.length() - 3))) {
      c.text(" />", e.pos());
    } else {
      c.text("/>", e.pos());
    }
  }

  /**
   * Whether the start tag gets the render's CSP nonce (#335): every {@code <script>} and {@code
   * <style>} the template writes where a browser reads it as an HTML element. One inside {@code
   * <svg>} or {@code <math>} (whose content a browser parses differently, decoding character
   * references) or inside a raw text element such as {@code <noscript>} or {@code <xmp>} (where it
   * is text) gets none, and fragments inserted there are rendered without the nonce ({@link
   * #insertionContext}). If the tracking is wrong, a script loses its nonce and does not run under
   * the policy; it never gains one. Markup written at runtime ({@code SafeHtml}) never gets one.
   * A nonce in the template itself is an error: it would be the same on every response, or a
   * second, conflicting nonce attribute (the browser keeps the first).
   */
  private boolean takesCspNonce(ElementPlan plan) {
    String lower = plan.element().name().toLowerCase(Locale.ROOT);
    if (!lower.equals("script") && !lower.equals("style")) {
      return false;
    }
    Optional<ElementPlan.Attr> own = plan.attribute("nonce");
    if (own.isPresent()) {
      ElementPlan.Attr a = own.get();
      SourcePos pos =
          a instanceof ElementPlan.StaticAttr
              ? ((ElementPlan.StaticAttr) a).source().pos()
              : ((ElementPlan.DynamicAttr) a).pos();
      diagnostics()
          .error(pos == null ? plan.element().pos() : pos, "a nonce on <" + lower + "> is not supported: Tessera"
              + " writes the render's CSP nonce (RenderContext.cspNonce, set per request by the Spring starter) on"
              + " every <script> and <style>; remove the nonce attribute (static, t:nonce or t:attr nonce=...)");
    }
    return !inForeign && rawTextHost == null;
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

  private Emitted dynamicAttr(Element e, ElementPlan.DynamicAttr d) {
    // Only attribute contexts: e.g. the SCRIPT (json) escaper does not escape '"'. A STYLE
    // attribute is attribute-escaped; GenericAttrProcessor admits only safe style values (#182).
    OutputContext ctx = d.context();
    String escaper =
        ctx == OutputContext.ATTRIBUTE || ctx == OutputContext.URL || ctx == OutputContext.URL_LIST
            || ctx == OutputContext.RESOURCE_URL || ctx == OutputContext.REFRESH || ctx == OutputContext.SCRIPT_ATTRIBUTE
            ? ctx.escaper()
            : ctx == OutputContext.STYLE ? OutputContext.ATTRIBUTE.escaper() : null;
    if (escaper == null) {
      diagnostics().error(d.pos(), "an expression cannot be used in attribute " + d.name() + " (" + d.context()
              + " context): write a literal value");
      return Emitted.EMPTY;
    }
    String v = freshName("v");
    Emitted attribute =
        Emitted.concat(
            Emitted.text(" " + d.name() + "=\"", d.pos()),
            Emitted.code(" "),
            Emitted.hole(Skeleton.Escaper.forMethod(escaper), e.name(), d.name(), d.pos(), v),
            Emitted.code(" "),
            Emitted.text('"', d.pos()));
    String before = "{ java.lang.Object " + v + " = " + d.valueCode() + "; if (";
    // A JSON value (SCRIPT_ATTRIBUTE, #246) is written even when null, as JSON null.
    return ctx == OutputContext.SCRIPT_ATTRIBUTE
        ? Emitted.wrap(before + "true) { ", attribute, " } }")
        : Emitted.opt(Skeleton.OptKind.ATTRIBUTE, d.pos(), before + v + " != null) { ", attribute, " } }");
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
}
