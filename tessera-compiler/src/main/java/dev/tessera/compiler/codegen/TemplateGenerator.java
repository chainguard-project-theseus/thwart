package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.GeneratedFile;
import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.codegen.attr.CsrfInjector;
import dev.tessera.compiler.codegen.attr.FieldProcessor;
import dev.tessera.compiler.codegen.attr.FragmentMethods;
import dev.tessera.compiler.codegen.attr.TextInlining;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.html.HtmlParser;
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
  /**
   * Elements whose start tag makes a browser drop a line feed right after it (HTML 13.2.6.4.7,
   * "in body": the next token is ignored if it is U+000A); see {@link #emit}.
   */
  private static final Set<String> LEADING_LINE_FEED = Backports.setOf("pre", "listing", "textarea");

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
  /** Whether the element whose content is being generated is a JSON {@code <script>} ({@link #inJsonScript}). */
  private boolean jsonScript;

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
    TextInlining.checkMustachesClosed(this, document.children());
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
    Map<String, String> declared = new LinkedHashMap<>();
    for (TemplateIndex.FragmentInfo f : info.fragments().values()) {
      declared.put(f.name(), info.parsingContexts().place(f.element()));
    }
    skeleton = new Skeleton.TemplateSkeleton(info.viewName(), bodyCode.seq(), fragments, declared);
    return Backports.listOf(new GeneratedFile(GeneratedFile.Kind.JAVA, info.className(), TemplateClassSource.render(info, constants, members, bodyCode.java())));
  }

  /**
   * {@code file}, a template class {@link #generate} wrote, marked as a verified {@code FLOW}
   * partial (#396): its {@code verifiedFlowPartial()} returns true.
   */
  public static GeneratedFile markFlowPartial(GeneratedFile file) {
    return new GeneratedFile(file.kind(), file.name(), TemplateClassSource.markFlowPartial(file.content()));
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
      if (HtmlParser.isAsciiAlphanumeric(c) || c == '_') {
        sb.append(c);
      }
    }
    if (sb.length() == 0 || HtmlParser.isAsciiDigit(sb.charAt(0))) {
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
  public boolean inJsonScript() {
    return jsonScript;
  }

  @Override
  public Emitted renderElement(Element element, Scope scope, Set<String> ignoredAttributes) {
    // Rendered standalone (fragment method, ~{::selector} helper): no preceding sibling text.
    String ws = whitespaceBefore.remove(element);
    // ... and rendered where it is inserted, which is checked there, not inside its source parent.
    String host = rawTextHost;
    boolean foreign = inForeign;
    String outerHost = hostElement;
    rawTextHost = null;
    inForeign = false;
    hostElement = null;
    try {
      Emitted.Builder c = builder();
      element(c, element, scope, ignoredAttributes);
      return c.build();
    } finally {
      hostElement = outerHost;
      rawTextHost = host;
      inForeign = foreign;
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
              || e.attribute("t:remove").map(Attribute::value).map(JavaStrings::trimJava).filter("tag"::equals).isPresent())) {
        boost = null;
      }
      boolean b = boost == null ? boosted : boost;
      boolean form = HtmlParser.asciiEqualsIgnoreCase(e.name(), "form");
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
    while (start > 0 && JavaStrings.isJavaWhitespace(raw.charAt(start - 1))) {
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
          && t.context() == OutputContext.TEXT) {
        int unescaped = TextInlining.firstUnescaped(t.raw());
        if (unescaped >= 0) {
          runtimeHtmlInRawText(t.pos().advance(t.raw(), unescaped), "[(...)]", rawTextHost);
        }
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
    boolean outerJson = jsonScript;
    String host = rawTextElement(e) ? e.name() : outer;
    // In raw text (<xmp>, <iframe>, <noembed>, <noframes>) the tag is text, so nothing is dropped.
    // Inside <noscript> the content is markup with scripting off, and raw text a browser never
    // renders with it on, so the line feed is written there too.
    boolean leadingLineFeed = (outer == null || HtmlParser.asciiEqualsIgnoreCase(outer, "noscript")) && !e.foreign()
        && LEADING_LINE_FEED.contains(HtmlParser.asciiLower(e.name()));
    // Processors (t:remove="all-but-first") and emit generate the content.
    rawTextHost = host;
    inForeign = outerForeign || HtmlParser.asciiEqualsIgnoreCase(e.name(), "svg")
        || HtmlParser.asciiEqualsIgnoreCase(e.name(), "math");
    jsonScript = Contexts.isJsonScript(e);
    if (!TextInlining.tagNotRendered(e)) {
      hostElement = HtmlParser.asciiLower(e.name());
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
        AttributeProcessors.forName(a.localName()).ifPresent(p -> p.process(this, a, plan));
      }
      if (HtmlParser.asciiEqualsIgnoreCase(e.name(), "form")) {
        CsrfInjector.apply(this, plan);
      }
      if (!plan.removed() && plan.replacement() == null) {
        CsrfInjector.checkSubmitter(this, plan);
      }
      CsrfInjector.checkStaticUrls(this, e);
      if (HtmlParser.asciiEqualsIgnoreCase(e.name(), "option")) {
        FieldProcessor.option(this, plan);
      }
      emit(c, plan, leadingLineFeed);
    } finally {
      hostElement = outerHost;
      rawTextHost = outer;
      inForeign = outerForeign;
      jsonScript = outerJson;
    }
  }

  /**
   * Whether a browser reads the content of {@code e} as raw text or RCDATA up to the first
   * {@code </name}: {@link Contexts#browserRawText} elements, {@code <textarea>} and {@code <title>}.
   * Tessera treats {@code <title>} in {@code <svg>} and {@code <math>} as RCDATA too: a browser
   * reads it as RCDATA in an HTML integration point ({@code <foreignObject>}, {@code <mtext>}) or
   * after a tag that ends foreign content ({@code <b>}).
   */
  private static boolean rawTextElement(Element e) {
    String lower = HtmlParser.asciiLower(e.name());
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

  /**
   * Writes the element. A browser drops a line feed right after a {@code <pre>}, {@code <listing>}
   * or {@code <textarea>} start tag ({@code leadingLineFeed}), so when the content may start with a
   * value, an inserted fragment or anything else but static text, one line feed is written after the
   * start tag: the browser drops it, and keeps a value's own leading line feed and a static line feed
   * after an empty value. Content that starts with static text is written as is, as Thymeleaf does.
   */
  private void emit(Emitted.Builder c, ElementPlan plan, boolean leadingLineFeed) {
    Element e = plan.element();
    List<ElementPlan.Wrapper> wrappers = plan.wrappers();
    for (ElementPlan.Wrapper w : wrappers) {
      c.open(w);
    }
    if (!plan.removed()) {
      if (plan.replacement() != null) {
        c.add(plan.replacement());
      } else {
        for (Emitted s : plan.beforeStartTagStatements()) {
          c.add(s);
        }
        if (!plan.tagOmitted() && e.selfClosing() && rawTextElement(e) && !opensSelfClosing(plan)) {
          selfClosingRawText(e);
        }
        // The content is generated first: whether the start tag gets v-pre depends on it.
        Emitted.Builder content = builder();
        for (Emitted s : plan.afterStartTagStatements()) {
          content.add(s);
        }
        if (plan.body() != null) {
          content.add(plan.body());
        } else {
          for (Node n : e.children()) {
            node(content, n, plan.scope());
          }
        }
        Emitted written = content.build();
        if (!plan.tagOmitted()) {
          startTag(c, plan, addsVPre(plan, written));
          if (leadingLineFeed && !startsWithStatic(written.nodes())) {
            c.text("\n", e.pos());
          }
        }
        c.add(written);
        if (!plan.tagOmitted() && (e.hasEndTag() || opensSelfClosing(plan))) {
          c.text("</" + e.name() + ">", e.pos());
        }
      }
    }
    for (int i = wrappers.size() - 1; i >= 0; i--) {
      c.close(wrappers.get(i));
    }
  }

  /** Whether {@code nodes} start with non-empty static text, or write nothing. */
  private static boolean startsWithStatic(List<Skeleton.Node> nodes) {
    for (Skeleton.Node n : nodes) {
      if (!(n instanceof Skeleton.Static)) {
        return false;
      }
      if (!((Skeleton.Static) n).text().isEmpty()) {
        return true;
      }
    }
    return true;
  }

  /**
   * Reports {@code <textarea/>} and the like written as is: a browser ignores the '/' and reads
   * the rest of the page as the element's text up to the first {@code </name}, which Tessera may
   * have read inside a comment or attribute value.
   */
  private void selfClosingRawText(Element e) {
    String lower = HtmlParser.asciiLower(e.name());
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

  /**
   * Whether the start tag of {@code plan}'s {@code <textarea>} or {@code <title>} gets
   * {@code v-pre} (#549): the framework table's RCDATA entry for Vue 3 and petite-vue
   * ({@link Contexts#VUE_SKIP}). Their text is RCDATA, where a comment is text, so a value's
   * {{ cannot be broken, and Vue 3 (in a mounted region) and petite-vue (mounted on the
   * whole document without {@code v-scope}) interpolate it. Both skip an element with
   * {@code v-pre}; anywhere else it is an inert unknown attribute. Added when the content is
   * computed ({@code content} writes anything but static text) and the element has no static
   * {@code v-pre} yet. A Vue directive or binding on the element, or a static {{ in its
   * content, would silently stop working under {@code v-pre}, so either is a build error.
   */
  private boolean addsVPre(ElementPlan plan, Emitted content) {
    Element e = plan.element();
    String lower = HtmlParser.asciiLower(e.name());
    if (!lower.equals("textarea") && !lower.equals("title") || !computed(content.nodes())) {
      return false;
    }
    List<ElementPlan.Attr> directives = new ArrayList<>();
    for (ElementPlan.Attr a : plan.attributes()) {
      String name = HtmlParser.asciiLower(a.name());
      if (name.equals(Contexts.VUE_SKIP)) {
        return false;
      }
      if (Contexts.isVueDirective(name)) {
        directives.add(a);
      }
    }
    for (ElementPlan.Attr a : directives) {
      SourcePos pos =
          a instanceof ElementPlan.StaticAttr ? ((ElementPlan.StaticAttr) a).source().pos() : ((ElementPlan.DynamicAttr) a).pos();
      diagnostics()
          .error(pos == null ? e.pos() : pos, a.name() + " on a <" + lower + "> with computed content is not supported:"
              + " Tessera adds v-pre there, because a comment cannot break {{ in a value in <" + lower + ">, and v-pre"
              + " would turn " + a.name() + " off; drop " + a.name() + ", or move the value into Vue (v-model or"
              + " v-text with the value in the app's data) instead of writing it with Tessera");
    }
    boolean braces = staticBraces(content.nodes());
    if (braces) {
      diagnostics()
          .error(e.pos(), "{{ in the static text of a <" + lower + "> with computed content is not supported: Tessera"
              + " adds v-pre there, because a comment cannot break {{ in a value in <" + lower + ">, and v-pre would"
              + " show the static {{ ... }} as text; write the whole content with Tessera, or bind it with Vue");
    }
    return directives.isEmpty() && !braces;
  }

  /** Whether {@code nodes} write anything but static text. */
  private static boolean computed(List<Skeleton.Node> nodes) {
    for (Skeleton.Node n : nodes) {
      if (n instanceof Skeleton.Seq ? computed(((Skeleton.Seq) n).nodes()) : !(n instanceof Skeleton.Static)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the static text in {@code nodes} may write {{: adjacent static text joins, also into
   * and out of conditions and loops; a value, call or other branch separates it.
   */
  private static boolean staticBraces(List<Skeleton.Node> nodes) {
    StringBuilder text = new StringBuilder();
    staticText(nodes, text);
    return text.indexOf("{{") >= 0;
  }

  private static void staticText(List<Skeleton.Node> nodes, StringBuilder out) {
    for (Skeleton.Node n : nodes) {
      if (n instanceof Skeleton.Static) {
        out.append(((Skeleton.Static) n).text());
      } else if (n instanceof Skeleton.Seq) {
        staticText(((Skeleton.Seq) n).nodes(), out);
      } else if (n instanceof Skeleton.Opt) {
        staticText(((Skeleton.Opt) n).body().nodes(), out);
      } else if (n instanceof Skeleton.Rep) {
        // Two rounds: the end of one meets the separator and the start of the next.
        staticText(((Skeleton.Rep) n).body().nodes(), out);
        staticText(((Skeleton.Rep) n).separator().nodes(), out);
        staticText(((Skeleton.Rep) n).body().nodes(), out);
      } else if (n instanceof Skeleton.Alt) {
        for (Skeleton.Seq branch : ((Skeleton.Alt) n).branches()) {
          out.append('\0');
          staticText(branch.nodes(), out);
        }
        out.append('\0');
      } else {
        out.append('\0');
      }
    }
  }

  private void startTag(Emitted.Builder c, ElementPlan plan, boolean vPre) {
    Element e = plan.element();
    boolean nonce = takesCspNonce(plan);
    String source = sourceStartTag(e);
    if (source != null && !nonce && !vPre && unchanged(e, plan)) {
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
    if (vPre) {
      // Static text in the skeleton, exactly as written, so the verifier parses it (§11.3 item 1).
      c.text(" " + Contexts.VUE_SKIP, e.pos());
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
    } else if (source != null && source.length() > 2 && HtmlParser.isHtmlWhitespace(source.charAt(source.length() - 3))) {
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
    String lower = HtmlParser.asciiLower(plan.element().name());
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
    // attribute is attribute-escaped; GenericAttrProcessor admits only safe style values (#182),
    // and only literals, numbers and the safe type in TYPED_ATTRIBUTE and TRUSTED_URL (#539).
    OutputContext ctx = d.context();
    String escaper =
        ctx == OutputContext.ATTRIBUTE || ctx == OutputContext.URL
            || ctx == OutputContext.RESOURCE_URL || ctx == OutputContext.REFRESH || ctx == OutputContext.SCRIPT_ATTRIBUTE
            ? ctx.escaper()
            : ctx == OutputContext.STYLE || ctx == OutputContext.TYPED_ATTRIBUTE || ctx == OutputContext.TRUSTED_URL
                ? OutputContext.ATTRIBUTE.escaper()
                : null;
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
