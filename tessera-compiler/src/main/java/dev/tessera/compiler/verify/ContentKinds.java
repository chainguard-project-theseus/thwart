package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.verify.engine.InsertionMode;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.TokenizerState;
import java.util.EnumSet;
import java.util.List;
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
 *
 * <p>Only one kind exists, FLOW: the bench corpus needs no other. FLOW is
 * balanced flow content: every element it opens it closes; it has no {@code form} element; and
 * its top-level elements are not list items ({@code li}, {@code dd}, {@code dt}), table parts,
 * {@code head}, {@code body}, {@code html} or {@code frameset}. Producers: every {@code
 * html.sanitize} policy (the producer test checks them), and {@code SafeHtml.escape} and {@code
 * concat} of FLOW values. A {@code Fragment} argument is verified where it lands.
 */
public final class ContentKinds {
  private ContentKinds() {}

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
   * The problem with FLOW HTML inserted in state {@code s}, or null if the value leaves
   * the parser in {@code s} (for {@code IN_TEMPLATE}: in {@code s}, or in the "in body" state a
   * flow start tag switches to there, see {@link #switchesMode}).
   *
   * <p>{@link Problem#RAW_NOT_FLOW}: not in data state; the current node isn't HTML; or the mode
   * isn't in body, in cell, in caption or in template. {@link Problem#RAW_CLOSES_ELEMENT}: an open
   * element that a flow start tag closes or adopts ({@link #closer}). Formatting elements waiting
   * to be reopened are reached only by error recovery, a {@link Problem#PARSE_ERROR}.
   */
  public static Problem problem(ParserState s) {
    return problem(s, Skeleton.Source.SAFE_HTML);
  }

  /**
   * The problem with HTML from {@code source} inserted in state {@code s}: as {@link #problem(ParserState)}
   * for FLOW, with {@link #closer(ParserState, Skeleton.Source)} for an HTML message.
   */
  public static Problem problem(ParserState s, Skeleton.Source source) {
    OpenElement current = s.currentNode();
    if (s.tokenizer() != TokenizerState.DATA || !s.pendingInput().isEmpty() || current == null
        || !OpenElement.HTML.equals(current.namespace()) || !FLOW_MODES.contains(s.mode())) {
      return Problem.RAW_NOT_FLOW;
    }
    return closer(s, source) == null ? null : Problem.RAW_CLOSES_ELEMENT;
  }

  /**
   * Whether FLOW HTML inserted in {@code s} (where {@link #problem} is null) may leave
   * the parser in another state: in {@code IN_TEMPLATE} mode, the first start tag of flow content
   * switches the mode (and the current template insertion mode) to "in body". The verifier then
   * also continues from the state after a representative flow element ({@link #REPRESENTATIVE}).
   */
  public static boolean switchesMode(ParserState s) {
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
   * </ul>
   *
   * <p>Not here: SVG or MathML above a cell, where a {@code </table>} in the value resets the mode
   * differently in validator.nu and the standard. Exclusion (a) rejects any {@code <svg>} or
   * {@code <math>} in a table part ({@link Rules#exclusion}).
   *
   * <p>Not here: three or more formatting entries of one name. One more drops the oldest from the
   * list (the Noah's Ark clause), but that element stays open, and in an error-free parse the list
   * changes the tree only through entries that are no longer open.
   *
   * <p>Not here, because FLOW content can't close them: an open {@code li}, {@code dd} or {@code dt}
   * (FLOW content writes those only inside its own list, whose {@code ul}/{@code ol}/{@code dl}
   * stops the start tag's search), and an open {@code form} (FLOW content has no {@code form},
   * whose start tag would be ignored there and whose end tag would close the open one). Rejecting
   * them would reject sanitized text in any list item or form (20 bench pages write it in a form).
   */
  static String closer(ParserState s) {
    return closer(s, Skeleton.Source.SAFE_HTML);
  }

  /**
   * As {@link #closer(ParserState)} for HTML from {@code source}. An HTML message is PHRASING:
   * the message policy writes only {@code b i em strong u s sub sup small mark code kbd
   * span br}, balanced and without nesting a formatting element in one of its name, and, for a
   * {@link Skeleton.Source#LINKED_HTML_MESSAGE}, {@code a}. A {@code SafeInlineHtml} is PHRASING
   * without links too: escaped text, and the sanitizer's inline mode, which writes only {@code
   * HtmlPolicy.INLINE_ELEMENTS} ({@code b i em strong small s u sub sup code kbd mark abbr cite q dfn
   * time span br wbr img}), balanced, under the same nesting rule. None of those closes an open
   * {@code p}, heading, {@code button}, {@code nobr} or {@code ruby}, so only an open {@code select}
   * and, for links, an open {@code a} are left.
   */
  static String closer(ParserState s, Skeleton.Source source) {
    List<OpenElement> stack = s.stack();
    for (OpenElement e : stack) {
      if (e.isHtml("select") || e.isHtml("option") || e.isHtml("optgroup")) {
        return "an open <" + e.localName() + ">";
      }
    }
    if (source == Skeleton.Source.HTML_MESSAGE || source == Skeleton.Source.SAFE_INLINE_HTML) {
      return null;
    }
    if (source == Skeleton.Source.LINKED_HTML_MESSAGE) {
      return openLink(s);
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
    return openLink(s);
  }

  /** "an open <a>" if an {@code a} start tag would run the adoption agency in {@code s}, else null. */
  private static String openLink(ParserState s) {
    List<Integer> list = s.activeFormatting();
    for (int i = list.size() - 1; i >= 0 && list.get(i) != ParserState.MARKER; i--) {
      if (s.activeFormattingElements().get(i).isHtml("a")) {
        return "an open <a>";
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
   * The signature of {@code s} as an exit state is compared with an entry state: without the
   * original insertion mode, unless it is live. The parser sets it on entering the "text" mode (and
   * validator.nu's held-back table text) and reads it only there; validator.nu keeps the last value
   * afterwards, so a {@code <textarea>}, {@code <script>} or {@code <template>} would otherwise look
   * like a change.
   */
  static String flowSignature(ParserState s) {
    String sig = s.signature();
    if (s.mode() == InsertionMode.TEXT || s.pendingTableText()) {
      return sig;
    }
    int at = sig.indexOf(" original=");
    int end = at < 0 ? -1 : sig.indexOf(' ', at + 1);
    return at < 0 || end < 0 ? sig : sig.substring(0, at) + sig.substring(end);
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
    return before.tokenizer() != after.tokenizer() ? "ends inside markup" : "changes how what follows is parsed";
  }
}
