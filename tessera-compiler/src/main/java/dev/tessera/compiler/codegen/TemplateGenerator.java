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
import dev.tessera.compiler.html.ParsingContexts;
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
 * ({@code TextInlining} for text nodes, {@code CsrfInjector} for {@code <form>} and for every
 * element's static submit URLs and {@code <base href>}, {@code FieldProcessor.option} for {@code <option>}, {@code FragmentMethods} once per template). Handles {@code t:params}/{@code t:import} on the
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
  /**
   * The element a browser reads as raw text or RCDATA whose content is being generated, or null.
   * See {@link ParsingContexts#rawTextElement}.
   */
  private String rawTextHost;
  /** Whether the content being generated is inside an {@code <svg>} or {@code <math>} element. */
  private boolean inForeign;
  /** Whether the element whose content is being generated is a JSON {@code <script>} ({@link #inJsonScript}). */
  private boolean jsonScript;
  /** The template whose class is being generated; {@link #template} differs while a copy is. */
  private TemplateIndex.TemplateInfo emitting;
  /** {@link #privateFragment} names, by fragment. */
  private Map<Skeleton.FragmentId, String> privateNames;
  /** The copies to generate, in order; grows while they are generated. */
  private List<Skeleton.FragmentId> pending;
  /** The copies generated last, each with the fragment it copies ({@link #copies}). */
  private List<Map.Entry<Skeleton.FragmentId, Skeleton.Seq>> copies;

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
    emitting = info;
    use(info);
    constants = new LinkedHashMap<>();
    members = new ArrayList<>();
    fresh = 0;
    privateNames = new LinkedHashMap<>();
    pending = new ArrayList<>();
    copies = new ArrayList<>();

    validate(document.rootElement(), document.children());
    TextInlining.checkBraces(this, document.children(), null);
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
    for (int i = 0; i < pending.size(); i++) {
      Skeleton.FragmentId id = pending.get(i);
      TemplateIndex.TemplateInfo t = index.template(id.view()).get();
      use(t);
      try {
        Emitted copy = FragmentMethods.method(this, t.fragments().get(id.name()), privateNames.get(id));
        copies.add(new java.util.AbstractMap.SimpleImmutableEntry<>(id, copy.seq()));
      } finally {
        use(info);
      }
    }
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

  /**
   * The private fragment copies {@link #generate} generated last, each with the fragment it
   * copies: the copy must write that fragment's skeleton, which the verifier checks.
   */
  public List<Map.Entry<Skeleton.FragmentId, Skeleton.Seq>> copies() {
    return copies;
  }

  /** Resolves names, source positions and whitespace against {@code t} ({@link #privateFragment}). */
  private void use(TemplateIndex.TemplateInfo t) {
    template = t;
    content = t.document().source().content();
    lineStarts = t.document().source().lineStarts();
    whitespaceBefore = new IdentityHashMap<>();
    indexWhitespace(t.document().children());
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
  public String privateFragment(TemplateIndex.TemplateInfo t, TemplateIndex.FragmentInfo f) {
    Skeleton.FragmentId id = new Skeleton.FragmentId(t.viewName(), f.name());
    String name = privateNames.get(id);
    if (name == null) {
      name = f.methodName() + "$" + privateNames.size();
      privateNames.put(id, name);
      if (!t.viewName().equals(emitting.viewName())) {
        pending.add(id);
      }
    }
    return name;
  }

  @Override
  public String whitespaceBefore(Element element) {
    return whitespaceBefore.get(element);
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
    String host = ParsingContexts.rawTextElement(e) ? e.name() : outer;
    // In raw text the tag is text, so nothing is dropped (and the content is static, #571).
    boolean leadingLineFeed = outer == null && !e.foreign() && LEADING_LINE_FEED.contains(HtmlParser.asciiLower(e.name()));
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
      CsrfInjector.checkBase(this, e);
      checkStaticIs(e);
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
        if (!plan.tagOmitted() && e.selfClosing() && ParsingContexts.rawTextElement(e) && !opensSelfClosing(plan)) {
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
          if (Contexts.browserRawText(e.name()) && computed(written.nodes())) {
            // A comment cannot break a value's {{ in raw text (#571): only static content there. The
            // verifier rejects every value there too (VALUE_IN_RAW_TEXT, #586); this also covers conditions and loops.
            diagnostics()
                .error(e.pos(), "a value, condition or loop inside <" + HtmlParser.asciiLower(e.name()) + "> is not"
                    + " supported: a browser reads its content as raw text, where nothing can break a {{ in a value"
                    + " that a client template would run as code; write the content as static text, or move the"
                    + " value outside the element");
          }
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
   * Whether the start tag of {@code plan}'s {@code <textarea>} or {@code <title>} gets the constant
   * attribute {@code v-pre} (#549, #586): the one framework-named output. Their text is RCDATA,
   * where a comment is text, so no output shape breaks a value's {{, and Vue and petite-vue
   * interpolate it in a mounted region; both skip an element with {@code v-pre}, and anywhere else
   * it is an inert unknown attribute. Added whenever the content is computed ({@code content}
   * writes anything but static text), unless a static {@code v-pre} is already written.
   */
  private static boolean addsVPre(ElementPlan plan, Emitted content) {
    String lower = HtmlParser.asciiLower(plan.element().name());
    if (!lower.equals("textarea") && !lower.equals("title") || !computed(content.nodes())) {
      return false;
    }
    for (ElementPlan.Attr a : plan.attributes()) {
      if (a instanceof ElementPlan.StaticAttr && HtmlParser.asciiLower(a.name()).equals(Contexts.VUE_SKIP)) {
        return false;
      }
    }
    return true;
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
      diagnostics()
          .error(posOf(own.get(), plan.element()), "a nonce on <" + lower + "> is not supported: Tessera"
              + " writes the render's CSP nonce (RenderContext.cspNonce, set per request by the Spring starter) on"
              + " every <script> and <style>; remove the nonce attribute (static, t:nonce or t:attr nonce=...)");
    }
    return !inForeign && rawTextHost == null;
  }

  /** Where {@code a} is written, or {@code e}'s position if it has none. */
  private static SourcePos posOf(ElementPlan.Attr a, Element e) {
    SourcePos pos =
        a instanceof ElementPlan.StaticAttr ? ((ElementPlan.StaticAttr) a).source().pos() : ((ElementPlan.DynamicAttr) a).pos();
    return pos == null ? e.pos() : pos;
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

  /**
   * A static {@code is} must be a valid custom element name ({@link Contexts#isCustomElementName},
   * #272, #584): any other value, such as {@code <component is="script">}, would let what reads
   * {@code is} render another element than the one classified. A computed one is FORBIDDEN.
   */
  private void checkStaticIs(Element e) {
    for (Attribute a : e.attributes()) {
      if (!a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "is") && !Contexts.isCustomElementName(a.value())) {
        diagnostics().error(a.pos(), "is=\"" + a.value() + "\" on <" + e.name() + "> is not allowed: is may only name a"
            + " custom element (a lower-case name with a '-', such as my-button); write the element you mean, such as"
            + " <my-button>");
      }
    }
  }

  private Emitted dynamicAttr(Element e, ElementPlan.DynamicAttr d) {
    // Only attribute contexts: e.g. the SCRIPT (json) escaper does not escape '"'. A STYLE
    // attribute is attribute-escaped; GenericAttrProcessor admits only safe style values (#182),
    // only literals and numbers in TYPED_ATTRIBUTE (#565), those or TrustedUrls in TRUSTED_URL (#539), and
    // neutral values (numbers, booleans, same-origin paths, literals) in NEUTRAL (#584).
    OutputContext ctx = d.context();
    String escaper =
        ctx == OutputContext.ATTRIBUTE || ctx == OutputContext.URL
            || ctx == OutputContext.RESOURCE_URL || ctx == OutputContext.REFRESH
            ? ctx.escaper()
            : ctx == OutputContext.STYLE || ctx == OutputContext.TYPED_ATTRIBUTE || ctx == OutputContext.TRUSTED_URL
                    || ctx == OutputContext.NEUTRAL
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
    return Emitted.opt(Skeleton.OptKind.ATTRIBUTE, d.pos(), before + v + " != null) { ", attribute, " } }");
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
