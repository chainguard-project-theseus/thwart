package dev.chainguard.thwart.compiler.codegen;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.CompilerEnv;
import dev.chainguard.thwart.compiler.GeneratedFile;
import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.codegen.attr.CsrfInjector;
import dev.chainguard.thwart.compiler.codegen.attr.FieldProcessor;
import dev.chainguard.thwart.compiler.codegen.attr.FragmentMethods;
import dev.chainguard.thwart.compiler.codegen.attr.TextInlining;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.expr.ExpressionParser;
import dev.chainguard.thwart.compiler.html.Contexts;
import dev.chainguard.thwart.compiler.html.HtmlParser;
import dev.chainguard.thwart.compiler.html.ParsingContexts;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.Comment;
import dev.chainguard.thwart.compiler.model.DocType;
import dev.chainguard.thwart.compiler.model.Document;
import dev.chainguard.thwart.compiler.model.Element;
import dev.chainguard.thwart.compiler.model.Node;
import dev.chainguard.thwart.compiler.model.OutputContext;
import dev.chainguard.thwart.compiler.model.Text;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.compiler.types.ExpressionCompiler;
import dev.chainguard.thwart.compiler.types.Scope;
import dev.chainguard.thwart.compiler.types.Variable;
import dev.chainguard.thwart.compiler.verify.Skeleton;
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
 * Generates the class for one template (shape in docs/architecture.md): walks the node tree,
 * emits static markup as string constants, builds an {@link ElementPlan} per element by running
 * {@link AttributeProcessors} in precedence order, and calls the per-template hooks
 * ({@code TextInlining} for text nodes, {@code CsrfInjector} for {@code <form>} and for every
 * element's static submit URLs and {@code <base href>}, {@code FieldProcessor.option} for {@code <option>}, {@code FragmentMethods} once per template). Handles {@code t:params}/{@code t:import} on the
 * root element, {@code <t:block>} (tag omitted), and rejects misplaced core attributes.
 * Implements {@link CodegenContext}; {@link TemplateClassSource} assembles the class text.
 *
 * <p>Elements without {@code t:} attributes whose plan is unchanged are copied from the source
 * verbatim (start tag included), so static markup renders byte-identical. Rebuilt start tags
 * keep attribute order and quoting but normalize the whitespace between attributes to one space.
 *
 * <p>Not thread-safe: one instance generates one template at a time.
 */
public final class TemplateGenerator implements CodegenContext {
  /** {@code t:} attributes that write HTML known only at runtime into the element. */
  private static final Set<String> RUNTIME_HTML = Backports.setOf("utext", "insert", "replace");
  /**
   * Elements whose start tag makes a browser drop a line feed right after it (HTML 13.2.6.4.7,
   * "in body": the next token is ignored if it is U+000A); see {@link #emit}.
   */
  private static final Set<String> LEADING_LINE_FEED = Backports.setOf("pre", "listing", "textarea");

  /**
   * Elements a browser parses by rules Thwart doesn't model: raw text a browser never renders as
   * markup ({@code xmp noembed noframes}) and {@code frameset} (applies only if no body content came
   * before it). {@code plaintext} is rejected where it is parsed.
   */
  private static final Set<String> UNSUPPORTED_ELEMENTS =
      Backports.setOf("xmp", "noembed", "noframes", "frameset");

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
  /** The skeleton of the template generated last. */
  private Skeleton.TemplateSkeleton skeleton;
  /** The fragment whose method is being generated, or null. */
  private TemplateIndex.FragmentInfo fragment;
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
    Scope scope = Scope.empty();
    for (TemplateIndex.Param p : info.params()) {
      scope = scope.with(new Variable(p.name(), p.type(), javaName(p.name())));
    }
    fragment = null;
    Emitted.Builder body = builder();
    for (Node n : info.paramsRejected() ? Collections.<Node>emptyList() : document.children()) {
      node(body, n, scope);
    }
    Emitted bodyCode = body.build();
    bodyCode.checkMethodSize(
        diagnostics(), new SourcePos(info.document().source().path(), 1, 1), "template '" + info.viewName() + "'");
    Map<String, Skeleton.Seq> fragments =
        info.paramsRejected() ? Collections.<String, Skeleton.Seq>emptyMap() : FragmentMethods.generate(this);
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
   * The skeleton of the template {@link #generate} generated last: exactly what its class writes.
   * Meaningful only if no errors were reported.
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
    return new Emitted.Builder(this::constant);
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
    rawTextHost = null;
    inForeign = false;
    try {
      Emitted.Builder c = builder();
      element(c, element, scope, ignoredAttributes);
      return c.build();
    } finally {
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
      for (Attribute a : e.thwartAttributes()) {
        if (AttributeProcessors.CORE.contains(a.localName()) && e != root) {
          diagnostics()
              .error(a.pos(), a.name() + " is only allowed on the root element (the first element of the template)");
        }
      }
      // A fragment is checked where it lands, so it is declared where it parses like that: HTML content.
      String place = template.parsingContexts().place(e);
      Attribute f = e.attribute(Attribute.PREFIX + "fragment").orElse(null);
      if (f != null && !place.equals(ParsingContexts.HTML)) {
        diagnostics().error(f.pos(), "t:fragment in " + ParsingContexts.describe(place) + " content is not supported: a"
            + " browser parses it there by other rules than where it is inserted; declare the fragment outside it"
            + " (directly under <body> or in its own file) and insert it there");
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
    boolean outerForeign = inForeign;
    boolean outerJson = jsonScript;
    String host = ParsingContexts.rawTextElement(e) ? e.name() : outer;
    // In raw text the tag is text, so nothing is dropped (and the content is static).
    boolean leadingLineFeed = outer == null && !e.foreign() && LEADING_LINE_FEED.contains(HtmlParser.asciiLower(e.name()));
    // Processors and emit generate the content.
    rawTextHost = host;
    boolean svgOrMath = HtmlParser.asciiEqualsIgnoreCase(e.name(), "svg")
        || HtmlParser.asciiEqualsIgnoreCase(e.name(), "math");
    inForeign = outerForeign || svgOrMath;
    // The outermost foreign element of this output: an <svg> or <math>, or a fragment's SVG or MathML root.
    boolean foreignTop = !outerForeign && (svgOrMath || e.foreign());
    if (UNSUPPORTED_ELEMENTS.contains(HtmlParser.asciiLower(e.name()))) {
      String lower = HtmlParser.asciiLower(e.name());
      diagnostics().error(e.pos(), "<" + lower + "> is not supported: a browser parses it by rules Thwart doesn't"
          + " check; " + (lower.equals("frameset") ? "use an ordinary layout, or an <iframe>"
              : lower.equals("xmp") ? "use <pre> with the text escaped (&lt; for <)"
              : "use <noscript> or ordinary content"));
    }
    jsonScript = Contexts.isJsonScript(e);
    try {
      ElementPlan plan = new ElementPlan(e, scope);
      if (e.name().equals("t:block")) {
        plan.omitTag();
      }
      List<Attribute> attrs = new ArrayList<>();
      for (Attribute a : e.thwartAttributes()) {
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
        AttributeProcessors.forName(a.localName()).process(this, a, plan);
      }
      if (HtmlParser.asciiEqualsIgnoreCase(e.name(), "form")) {
        CsrfInjector.apply(this, plan);
      }
      if (!plan.removed() && plan.replacement() == null) {
        CsrfInjector.checkSubmitter(this, plan);
      }
      CsrfInjector.checkBase(this, e);
      checkStaticAttributes(e);
      if (HtmlParser.asciiEqualsIgnoreCase(e.name(), "option")) {
        FieldProcessor.option(this, plan);
      }
      emit(c, plan, leadingLineFeed, foreignTop);
    } finally {
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
  private void emit(Emitted.Builder c, ElementPlan plan, boolean leadingLineFeed, boolean foreignTop) {
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
        // The content is generated first: the raw-text check below needs it.
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
        if (foreignTop) {
          foreignContent(e, plan, written);
        }
        if (!plan.tagOmitted()) {
          if (Contexts.browserRawText(e.name()) && computed(written.nodes())) {
            // Raw text decodes no character reference, so no escaper fits: only static content there. The
            // verifier rejects every value there too (VALUE_IN_RAW_TEXT); this also covers conditions and loops.
            diagnostics()
                .error(e.pos(), "a value, condition or loop inside <" + HtmlParser.asciiLower(e.name()) + "> is not"
                    + " supported: a browser reads its content as raw text, which decodes no escaping; write the"
                    + " content as static text, or move the value outside the element");
          }
          startTag(c, plan);
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
   * the rest of the page as the element's text up to the first {@code </name}, which Thwart may
   * have read inside a comment or attribute value.
   */
  private void selfClosingRawText(Element e) {
    String lower = HtmlParser.asciiLower(e.name());
    if (inForeign && lower.equals("title")) {
      diagnostics()
          .error(e.pos(), "<title/> is not supported inside <svg> or <math>: Thwart cannot tell an SVG title"
              + " from an HTML one (in <foreignObject> or after <b>), after which a browser reads the rest of the"
              + " page as title text; write <title></title>");
      return;
    }
    diagnostics()
        .error(e.pos(), "<" + lower + "/> is not self-closing in HTML: the browser treats everything after it as "
            + lower + "; write <" + lower + " ...></" + lower + ">");
  }

  /** Whether {@code nodes} write anything but static text. */
  private static boolean computed(List<Skeleton.Node> nodes) {
    return firstComputed(nodes) != null;
  }

  /** Where the first node of {@code nodes} that isn't static text is, or null. */
  private static SourcePos firstComputed(List<Skeleton.Node> nodes) {
    for (Skeleton.Node n : nodes) {
      SourcePos at = n instanceof Skeleton.Seq ? firstComputed(((Skeleton.Seq) n).nodes())
          : n instanceof Skeleton.Static ? null : n.pos();
      if (at != null) {
        return at;
      }
    }
    return null;
  }

  /**
   * Reports the first computed value, condition, loop or fragment call in or on {@code e}, the outermost
   * {@code <svg>} or {@code <math>}: a browser parses foreign content by its own rules
   * (integration points, CDATA, presentation attributes that are CSS), which Thwart doesn't model.
   * Control flow on the element itself ({@code <svg t:if>}, {@code t:replace}) is outside it and allowed.
   */
  private void foreignContent(Element e, ElementPlan plan, Emitted written) {
    SourcePos at = null;
    for (ElementPlan.Attr a : plan.attributes()) {
      if (at == null && a instanceof ElementPlan.DynamicAttr) {
        at = ((ElementPlan.DynamicAttr) a).pos();
      }
    }
    if (at == null) {
      at = firstComputed(written.nodes());
    }
    if (at != null) {
      diagnostics().error(at, "a value, condition, loop or fragment inside <" + HtmlParser.asciiLower(e.name())
          + "> is not supported: " + Contexts.foreign(e.name()));
    }
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
        c.add(dynamicAttr((ElementPlan.DynamicAttr) a));
      }
    }
    if (nonce) {
      String n = freshName("nonce");
      c.add(
          Emitted.opt(
              Skeleton.OptKind.CSP_NONCE,
              e.pos(),
              "{ java.util.Optional<java.lang.String> " + n + " = ctx.cspNonce(); if (" + n + ".isPresent()) { ",
              Emitted.attributeValue(Skeleton.Escaper.ATTR, "nonce", e.pos(), n + ".get()"),
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
   * Whether the start tag gets the render's CSP nonce: every {@code <script>} and {@code
   * <style>} the template writes where a browser reads it as an HTML element. One inside {@code
   * <svg>} or {@code <math>} (whose content a browser parses differently, decoding character
   * references) or inside a raw text element such as {@code <noscript>} (where it
   * is text) gets none. A fragment is rendered with the caller's {@code ctx}: its own start tags
   * decide, as nothing is inserted inside raw text, {@code <svg>} or {@code <math>} (build errors),
   * so a fragment never lands in one. If the tracking is wrong, a script loses its nonce and does not
   * run under the policy; it never gains one. Markup written at runtime ({@code SafeHtml}) never gets one.
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
          .error(posOf(own.get(), plan.element()), "a nonce on <" + lower + "> is not supported: Thwart"
              + " writes the render's CSP nonce (RenderContext.cspNonce, set per request by the Spring starter) on"
              + " every <script> and <style>; remove the nonce attribute");
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
    if (!e.thwartAttributes().isEmpty() || e.attribute("xmlns:t").isPresent()) {
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
   * A static {@code is} must be a valid custom element name ({@link Contexts#isCustomElementName}):
   * any other value, such as {@code <component is="script">}, would let what reads
   * {@code is} render another element than the one classified. A computed one is FORBIDDEN. Static
   * inline script ({@code on*}, {@code javascript:} URLs) is rejected: {@link Contexts#staticScript}.
   * So is a namespaced name outside {@code t:} whose prefix has no '-' ({@code th:}, {@code sec:},
   * {@code layout:}, {@code T:}, {@code foo:}; not {@code xml:}, {@code xmlns:}, {@code xlink:} or a
   * directive such as {@code v-on:}), and {@code data-th-}/{@code data-sec-}: it would drop its effect and print its
   * source (#801).
   */
  private void checkStaticAttributes(Element e) {
    for (Attribute a : e.attributes()) {
      if (a.isThwart()) {
        continue;
      }
      if (a.name().matches("(?is)(?!(xml|xmlns|xlink):)[a-z0-9_]+:.*|data-(th|sec)-.*")) {
        diagnostics().error(a.pos(), a.name() + " on <" + e.name() + "> is not allowed: it is a prefixed attribute"
            + " that Thwart would print as text; write the t: attribute it stands for (thwart-migrate rewrites th:"
            + " ones, guide templates.md, Expressions and attributes) or remove it");
      }
      if (HtmlParser.asciiEqualsIgnoreCase(a.name(), "is") && !Contexts.isCustomElementName(a.value())) {
        diagnostics().error(a.pos(), "is=\"" + a.value() + "\" on <" + e.name() + "> is not allowed: is may only name a"
            + " custom element (a lower-case name with a '-', such as my-button); write the element you mean, such as"
            + " <my-button>");
      }
      String script = Contexts.staticScript(e, a.name(), a.value());
      if (script != null) {
        diagnostics().error(a.pos(), a.name() + " on <" + e.name() + "> is not allowed: " + script);
      }
    }
  }

  private Emitted dynamicAttr(ElementPlan.DynamicAttr d) {
    // Only attribute escapers: e.g. the SCRIPT (json) escaper does not escape '"'.
    // GenericAttrProcessor admits only safe style values in STYLE, literals and numbers in
    // TYPED_ATTRIBUTE and neutral values in NEUTRAL.
    Skeleton.Escaper escaper = d.context().attributeEscaper();
    if (escaper == null) {
      diagnostics().error(d.pos(), "an expression cannot be used in attribute " + d.name() + " (" + d.context()
              + " context): write a literal value");
      return Emitted.EMPTY;
    }
    String v = freshName("v");
    Emitted attribute = Emitted.attributeValue(escaper, d.name(), d.pos(), v);
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
