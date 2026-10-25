package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Content kinds (#329, docs/context-model.md §5): what HTML known only at render time may be, as
 * a predicate on the parser state where it is inserted. The build-time verifier checks the
 * predicate at every {@link Skeleton.RawInsert} and then takes the state after the value to be the
 * state before it. A kind is two promises that only hold together:
 *
 * <ul>
 *   <li><b>The producer</b> writes balanced content of the kind: every element it opens it closes,
 *       and its markup never closes, reopens or adopts an element it didn't open.
 *   <li><b>The insertion point</b> is a state in which such content can't touch anything open: the
 *       {@linkplain #problem predicate} below.
 * </ul>
 *
 * <p>Then the parser leaves the value in the state it entered it, whatever the value is, so the
 * rest of the template parses as the verifier saw it.
 */
public final class ContentKinds {
  private ContentKinds() {}

  /** The kinds. Only {@link #FLOW} exists: the bench corpus needs no other (see the #329 PR). */
  public enum Kind {
    /**
     * Balanced flow content: every element it opens it closes; it has no {@code form} element;
     * and its top-level elements are not list items ({@code li}, {@code dd}, {@code dt}), table
     * parts, {@code head}, {@code body}, {@code html} or {@code frameset}. Producers: every
     * {@code html.sanitize} policy (the producer test checks them), {@code SafeHtml.escape} and
     * {@code concat} of FLOW values. A rendered template ({@code SafeHtml.of}, a {@code Fragment}
     * value from Java) is FLOW if it is a partial of such content, which the verifier can't see at
     * the insertion point: like {@code SafeHtml.unchecked}, that is the caller's promise.
     */
    FLOW
  }

  /** Modes in which a flow element is handled by the "in body" rules and leaves the mode as it was. */
  private static final Set<InsertionMode> FLOW_MODES =
      EnumSet.of(InsertionMode.IN_BODY, InsertionMode.IN_CELL, InsertionMode.IN_CAPTION, InsertionMode.IN_TEMPLATE);

  /** HTML elements that end the default scope ("has an element in scope"). */
  private static final Set<String> SCOPE_HTML =
      Backports.setOf("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template");

  private static final Set<String> SCOPE_MATHML = Backports.setOf("mi", "mo", "mn", "ms", "mtext", "annotation-xml");

  private static final Set<String> SCOPE_SVG = Backports.setOf("foreignObject", "desc", "title");

  private static final Set<String> HEADINGS = Backports.setOf("h1", "h2", "h3", "h4", "h5", "h6");

  /**
   * The problem with HTML of {@code kind} inserted in state {@code s}, or null if the value leaves
   * the parser in {@code s} (for {@code IN_TEMPLATE}: in {@code s}, or in the "in body" state a
   * flow start tag switches to there, see {@link #switchesMode}).
   *
   * <p>{@link Problem#RAW_NOT_FLOW}: not in data state; the current node isn't HTML; the mode isn't
   * in body, in cell, in caption or in template; or the next character token or start tag would
   * first reconstruct formatting elements. {@link Problem#RAW_CLOSES_ELEMENT}: an open element
   * that a flow start tag closes or adopts ({@link #closer}).
   */
  public static Problem problem(Kind kind, ParserState s) {
    OpenElement current = s.currentNode();
    if (s.tokenizer() != TokenizerState.DATA || !s.pendingInput().isEmpty() || current == null
        || !OpenElement.HTML.equals(current.namespace()) || !FLOW_MODES.contains(s.mode())
        || s.reconstructionPending()) {
      return Problem.RAW_NOT_FLOW;
    }
    return closer(s) == null ? null : Problem.RAW_CLOSES_ELEMENT;
  }

  /**
   * Whether HTML of {@code kind} inserted in {@code s} (where {@link #problem} is null) may leave
   * the parser in another state: in {@code IN_TEMPLATE} mode, the first start tag of flow content
   * switches the mode (and the current template insertion mode) to "in body". The verifier then
   * also continues from the state after a representative flow element ({@link #REPRESENTATIVE}).
   */
  public static boolean switchesMode(Kind kind, ParserState s) {
    return s.mode() == InsertionMode.IN_TEMPLATE;
  }

  /**
   * A flow element that stands for any start tag of the kind in {@link #switchesMode}: in template
   * mode every flow start tag switches the same way, and in the states the predicate accepts it
   * closes nothing.
   */
  public static final String REPRESENTATIVE = "<span></span>";

  /**
   * The open element (in words) that a start tag of flow content would close, reopen or adopt in
   * {@code s}, or null. Per the "in body" rules of the HTML standard (§13.2.6.4.7):
   *
   * <ul>
   *   <li>{@code p} in button scope: {@code address article aside blockquote center details dialog
   *       dir div dl fieldset figcaption figure footer header hgroup main menu nav ol p search
   *       section summary ul}, the headings, {@code pre listing form hr table xmp} close it.
   *   <li>a heading as the current node: a heading start tag pops it.
   *   <li>{@code a} in the list of active formatting elements after the last marker: an {@code a}
   *       start tag runs the adoption agency on it.
   *   <li>{@code nobr} in scope: a {@code nobr} start tag adopts it. {@code button} in scope: a
   *       {@code button} start tag closes it. {@code ruby} in scope: {@code rb rp rt rtc} close
   *       what is open above it.
   *   <li>{@code select}, {@code option} or {@code optgroup} open: their start tags, and in
   *       customizable {@code <select>} parsing also {@code input}, {@code textarea} and others,
   *       close them.
   *   <li>Three or more formatting entries of one name after the last marker: one more is
   *       dropped from the list (the Noah's Ark clause), whatever its attributes.
   * </ul>
   *
   * <p>Not here, because FLOW content can't close them: an open {@code li}, {@code dd} or {@code dt}
   * (FLOW content writes those only inside its own list, whose {@code ul}/{@code ol}/{@code dl}
   * stops the start tag's search), and an open {@code form} (FLOW content has no {@code form},
   * whose start tag would be ignored there and whose end tag would close the open one). Rejecting
   * them would reject sanitized text in any list item or form (#329 PR: 20 bench pages in forms).
   */
  static String closer(ParserState s) {
    List<OpenElement> stack = s.stack();
    for (OpenElement e : stack) {
      if (e.isHtml("select") || e.isHtml("option") || e.isHtml("optgroup")) {
        return "an open <" + e.localName() + ">";
      }
    }
    if (inScope(stack, "p", true)) {
      return "an open <p>";
    }
    OpenElement current = s.currentNode();
    if (current != null && OpenElement.HTML.equals(current.namespace()) && HEADINGS.contains(current.localName())) {
      return "an open <" + current.localName() + ">";
    }
    for (String name : new String[] {"button", "nobr", "ruby"}) {
      if (inScope(stack, name, false)) {
        return "an open <" + name + ">";
      }
    }
    Map<String, Integer> counts = new HashMap<>();
    List<Integer> list = s.activeFormatting();
    for (int i = list.size() - 1; i >= 0 && list.get(i) != ParserState.MARKER; i--) {
      OpenElement e = s.activeFormattingElements().get(i);
      if (e.isHtml("a")) {
        return "an open <a>";
      }
      Integer n = counts.get(e.localName());
      counts.put(e.localName(), n == null ? 1 : n + 1);
      if (n != null && n + 1 >= 3) {
        return "three open <" + e.localName() + "> elements";
      }
    }
    return null;
  }

  /** Whether an HTML {@code name} is in scope (button scope if {@code button}). */
  private static boolean inScope(List<OpenElement> stack, String name, boolean button) {
    for (int i = stack.size() - 1; i >= 0; i--) {
      OpenElement e = stack.get(i);
      if (e.isHtml(name)) {
        return true;
      }
      if (boundary(e) || button && e.isHtml("button")) {
        return false;
      }
    }
    return false;
  }

  private static boolean boundary(OpenElement e) {
    String n = e.localName();
    if (OpenElement.HTML.equals(e.namespace())) {
      return SCOPE_HTML.contains(n);
    }
    if (OpenElement.MATHML.equals(e.namespace())) {
      return SCOPE_MATHML.contains(n);
    }
    return OpenElement.SVG.equals(e.namespace()) && SCOPE_SVG.contains(n);
  }

  /**
   * In words, how {@code after} differs from {@code before} (states of one parser): which elements
   * were closed or left open, else that the parser is in another state.
   */
  static String difference(ParserState before, ParserState after) {
    String closer = closer(before);
    if (closer != null && !before.stack().equals(after.stack())) {
      // Elements compare by name: a <p> that closed the open <p> and opened its own looks unchanged.
      return "closes " + closer + " around it";
    }
    List<OpenElement> a = before.stack();
    List<OpenElement> b = after.stack();
    int common = 0;
    while (common < a.size() && common < b.size() && a.get(common).equals(b.get(common))) {
      common++;
    }
    if (common < a.size()) {
      return "closes the <" + a.get(common).localName() + "> open around it";
    }
    if (common < b.size()) {
      return "leaves <" + b.get(common).localName() + "> open";
    }
    return before.tokenizer() != after.tokenizer() ? "ends inside markup"
        : before.reconstructionPending() != after.reconstructionPending() ? "leaves formatting elements to reopen"
        : "changes how what follows is parsed";
  }
}
