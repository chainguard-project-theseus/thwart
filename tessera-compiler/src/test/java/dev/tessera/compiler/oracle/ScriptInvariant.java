package dev.tessera.compiler.oracle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Comment;
import org.mozilla.javascript.ast.IdeErrorReporter;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.UpdateExpression;

/**
 * The script-slot check (#319, design §5.4 and §5.7 step 3 of docs/security-architecture.md): in a
 * JS {@code <script>} whose text holds a slot marker, each value must be <em>exactly one literal in
 * expression position</em>, and the hostile script must parse to the same token structure as the
 * benign one everywhere outside the values.
 *
 * <p>Input is script text as a spec HTML parser's tree has it (#318), not the raw render, so an SVG
 * {@code <script>} has its character references decoded ({@code &quot;} is {@code "}). The benign
 * render fills each slot with its marker (e.g. {@code tqm0001}); the compiler writes that as the
 * JSON string {@code "tqm0001"}. The hostile render fills it with a hostile value whose payload call
 * is rewritten to the marker (e.g. {@code tqm0001()}).
 *
 * <p>Method, parsing with Rhino ({@value #ENGINE}) at its newest language level, never a hand-written
 * lexer:
 *
 * <ol>
 *   <li>Benign: every occurrence of a marker must be a whole string literal whose value is exactly
 *       the marker. A marker in an identifier is {@code LIVE_SCRIPT}; in a longer string, a template
 *       literal, a regex or a comment it is {@code JS_NOT_LITERAL}.
 *   <li>Benign doesn't parse: if Rhino's first error is at a marker, the marker broke the syntax
 *       ({@code JS_NOT_LITERAL}, e.g. {@code var a = ""tqm0001"";} from a quoted hole or a
 *       regex/division mix-up). Otherwise {@code JS_UNPARSABLE}: <em>unchecked</em>, counted by the
 *       gate, never a pass (Rhino lacks some syntax, see below).
 *   <li>Hostile doesn't parse while benign does: {@code JS_NOT_LITERAL} (the value changed the
 *       script's syntax).
 *   <li>Both parse: walk the ASTs in parallel. Outside the slots they must match node for node
 *       (class, token type, and source text of leaves); at each slot the hostile node must be a JSON
 *       literal (string, number, {@code true}/{@code false}/{@code null}, or an array/object literal
 *       of those with string keys). Then the source text outside the slots must be identical, which
 *       covers comments and whitespace too. Any difference is {@code JS_NOT_LITERAL}.
 *   <li>A marker anywhere in the hostile script outside the aligned slot literals is
 *       {@code JS_NOT_LITERAL}, or {@code LIVE_SCRIPT} when it is (part of) an identifier, which
 *       includes a call target.
 * </ol>
 *
 * <p><b>Limits (known false negatives, listed by the gate #324).</b> Rhino 1.8.1 at
 * {@code VERSION_ECMASCRIPT} doesn't parse ES modules ({@code import}/{@code export}), classes,
 * class fields, {@code for await}, or object rest/spread. A script using them is {@code JS_UNPARSABLE}
 * unless Rhino's first error is at a marker; the Node job (#322, acorn) covers those. E4X is off, so
 * {@code <} is never XML. A correctly escaped value in a quoted hole ({@code "[[s]]"}) renders
 * byte-identically to an unquoted one, so the render alone can't tell them apart; the compiler
 * rejects quoted holes (#287), and both are safe when the escaper is right.
 *
 * <p><b>Division of labour with the HTML checks (#318).</b> This check sees only the script element's
 * text. A value that ends the element ({@code </script>}), opens a comment or the double-escaped state
 * ({@code <!--<script}), or otherwise changes where the element ends is the HTML layer's finding
 * ({@code STRUCTURE_CHANGED}); here it only shows up as a hostile text that doesn't parse or doesn't
 * match. Annex B HTML-like comments ({@code <!--}, and {@code -->} at the start of a line) are JS
 * syntax and are checked here. Rhino 1.8.1 doesn't treat a multi-line comment holding a line break as
 * a line break, so it reads {@code x /*\n*&#47; --> v} as a postfix {@code --}; such a script is
 * treated as unparsable rather than checked with Rhino's reading.
 */
public final class ScriptInvariant {

  /** The engine name in {@link Finding#engine()} and {@code known-holes.txt}. */
  public static final String ENGINE = "rhino";

  private ScriptInvariant() {}

  /**
   * Checks one script element.
   *
   * @param benign the script text of the benign render (each slot holds its marker)
   * @param hostile the script text of the hostile render
   * @param markers the slot markers that occur in this script
   * @param where the caller's location for findings (scripting flag and node path); a detail is
   *     appended
   * @param value the hostile value, copied into each finding
   * @return the findings; empty when every slot is exactly one literal and nothing else changed
   */
  public static List<Finding> check(
      String benign, String hostile, Collection<String> markers, String where, String value) {
    Set<String> marks = new LinkedHashSet<>(markers);
    if (marks.isEmpty() || marks.contains("")) {
      throw new IllegalArgumentException("markers must be non-empty strings: " + markers);
    }
    List<Finding> out = new ArrayList<>();

    Parsed b = parse(benign);
    if (b.root == null) {
      int at = b.errorOffset;
      String marker = markerAt(benign, marks, at);
      if (marker != null) {
        out.add(finding(Finding.Kind.JS_NOT_LITERAL, where,
            "benign script doesn't parse at marker " + marker + " (" + b.error + ") " + pos(benign, at), value));
      } else {
        out.add(finding(Finding.Kind.JS_UNPARSABLE, where,
            "benign script doesn't parse (" + b.error + ") " + pos(benign, at), value));
      }
      // The hostile script can still show a live marker.
      Parsed h = parse(hostile);
      if (h.root != null) {
        liveNames(h, hostile, marks, where, value, out);
      }
      return dedupe(out);
    }

    // 1. Benign: every marker occurrence is a whole slot literal.
    List<AstNode> slots = new ArrayList<>();
    for (AstNode n : b.nodes) {
      if (n instanceof StringLiteral s && marks.contains(s.getValue())) {
        slots.add(n);
      }
    }
    boolean benignOk = markersOnlyIn(b, benign, marks, slots, "benign", where, value, out);

    // 2. Hostile parses.
    Parsed h = parse(hostile);
    if (h.root == null) {
      out.add(finding(Finding.Kind.JS_NOT_LITERAL, where,
          "hostile script doesn't parse though the benign one does (" + h.error + ") " + pos(hostile, h.errorOffset),
          value));
      return dedupe(out);
    }
    if (!benignOk) {
      liveNames(h, hostile, marks, where, value, out);
      return dedupe(out);
    }

    // 3. Parallel walk: same shape outside the slots, a JSON literal at each slot.
    Set<AstNode> slotSet = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    slotSet.addAll(slots);
    List<AstNode> hostileSlots = new ArrayList<>();
    String diff = align(b, b.root, h, h.root, slotSet, hostileSlots);
    if (diff == null && !cut(benign, slots).equals(cut(hostile, hostileSlots))) {
      diff = "hostile script text differs from the benign one outside the slot literals";
    }
    if (diff != null) {
      out.add(finding(Finding.Kind.JS_NOT_LITERAL, where, diff, value));
      liveNames(h, hostile, marks, where, value, out);
      return dedupe(out);
    }

    // 4. Hostile: no marker outside the slot literals.
    markersOnlyIn(h, hostile, marks, hostileSlots, "hostile", where, value, out);
    return dedupe(out);
  }

  // ---- parsing ----

  private record Parsed(
      AstRoot root, List<AstNode> nodes, Map<AstNode, List<AstNode>> children, String error, int errorOffset) {}

  private static Parsed parse(String text) {
    Parsed p = parse(text, true);
    if (p.root == null) {
      return p;
    }
    // In IDE mode Rhino swallows its own "nesting too deep" StackOverflowError and returns a partial
    // AST with no error. Parse again outside IDE mode, where any error throws, to fail closed.
    Parsed strict = parse(text, false);
    if (strict.root == null) {
      return new Parsed(null, List.of(), Map.of(), strict.error, -1);
    }
    return p;
  }

  private static Parsed parse(String text, boolean ideMode) {
    CompilerEnvirons env = new CompilerEnvirons();
    env.setLanguageVersion(Context.VERSION_ECMASCRIPT);
    // Comments are compared as source text outside the slots. Recording them as nodes also makes
    // Rhino 1.8.1 reject ASI before a trailing comment at the end of input (`f() /* x */`).
    env.setRecordingComments(false);
    env.setXmlAvailable(false);
    env.setRecoverFromErrors(false);
    // IDE mode reports errors with absolute offsets; it also recovers, so any error means "no parse".
    env.setIdeMode(ideMode);
    Errors errors = new Errors();
    env.setErrorReporter(errors);
    AstRoot root;
    try {
      root = new Parser(env, errors).parse(text, "script.js", 1);
    } catch (RuntimeException | StackOverflowError e) {
      String msg = errors.message != null ? errors.message : String.valueOf(e.getMessage());
      return new Parsed(null, List.of(), Map.of(), msg, errors.offset);
    }
    if (errors.message != null) {
      return new Parsed(null, List.of(), Map.of(), errors.message, errors.offset);
    }
    if (!ideMode) {
      return new Parsed(root, List.of(), Map.of(), null, -1);
    }
    List<AstNode> nodes = new ArrayList<>();
    Map<AstNode, List<AstNode>> children = new IdentityHashMap<>();
    root.visit(n -> {
      if (n instanceof Comment) {
        return false; // comments are compared as source text outside the slots
      }
      nodes.add(n);
      if (n != root) {
        children.computeIfAbsent(n.getParent(), k -> new ArrayList<>()).add(n);
      }
      return true;
    });
    for (AstNode n : nodes) {
      int at = postfixAfterLineBreak(text, n);
      if (at >= 0) {
        return new Parsed(null, List.of(), Map.of(),
            "Rhino read `++`/`--` after a line break inside a comment as postfix; the spec inserts a"
                + " semicolon there, and a `-->` there starts an HTML-like comment", at);
      }
    }
    return new Parsed(root, nodes, children, null, -1);
  }

  /**
   * Rhino 1.8.1 doesn't count a line terminator inside a multi-line comment as a line break: it parses
   * {@code x /*\n*&#47; --> "v"} as {@code x-- > "v"}, while the spec (and V8) treats the
   * {@code -->} as an Annex B HTML-like comment, so a raw line terminator in the value would end that
   * comment and run the rest. A postfix operator whose operand ends before a line terminator is never
   * valid JS, so treat it as "doesn't parse" (fail closed). Returns the operator's offset, or -1.
   */
  private static int postfixAfterLineBreak(String text, AstNode n) {
    if (!(n instanceof UpdateExpression u) || !u.isPostfix() || u.getOperand() == null) {
      return -1;
    }
    int from = u.getOperand().getAbsolutePosition() + u.getOperand().getLength();
    int to = u.getAbsolutePosition() + u.getLength() - 2;
    if (from < 0 || to > text.length() || from > to) {
      return u.getAbsolutePosition(); // spans we can't trust: fail closed
    }
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
        return to;
      }
    }
    return -1;
  }

  /** Records Rhino's first error; ignores warnings. */
  private static final class Errors implements IdeErrorReporter {
    String message;
    int offset = -1;

    @Override
    public void error(String message, String sourceName, int offset, int length) {
      if (this.message == null) {
        this.message = message;
        this.offset = offset;
      }
    }

    @Override
    public void warning(String message, String sourceName, int offset, int length) {}

    @Override
    public void error(String message, String sourceName, int line, String lineSource, int lineOffset) {
      if (this.message == null) {
        this.message = message;
      }
    }

    @Override
    public void warning(String message, String sourceName, int line, String lineSource, int lineOffset) {}

    @Override
    public EvaluatorException runtimeError(
        String message, String sourceName, int line, String lineSource, int lineOffset) {
      error(message, sourceName, line, lineSource, lineOffset);
      return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
    }
  }

  // ---- checks ----

  /**
   * Checks that every occurrence of a marker in {@code text} lies inside one of {@code allowed}.
   * Reports each other occurrence: {@code LIVE_SCRIPT} in an identifier, else {@code JS_NOT_LITERAL}.
   */
  private static boolean markersOnlyIn(
      Parsed p, String text, Set<String> marks, List<AstNode> allowed, String side, String where, String value,
      List<Finding> out) {
    boolean ok = true;
    for (String m : marks) {
      for (int i = text.indexOf(m); i >= 0; i = text.indexOf(m, i + 1)) {
        if (inside(allowed, i, m.length())) {
          continue;
        }
        ok = false;
        AstNode at = innermost(p, i, m.length());
        if (at instanceof Name) {
          out.add(finding(Finding.Kind.LIVE_SCRIPT, where,
              side + " script has marker " + m + " as an identifier " + pos(text, i), value));
        } else {
          String what = at == null ? "a comment" : describe(at);
          out.add(finding(Finding.Kind.JS_NOT_LITERAL, where,
              side + " script has marker " + m + " inside " + what + ", not as a whole literal " + pos(text, i), value));
        }
      }
    }
    return ok;
  }

  /** Any identifier containing a marker is live script. */
  private static void liveNames(
      Parsed p, String text, Set<String> marks, String where, String value, List<Finding> out) {
    for (AstNode n : p.nodes) {
      if (n instanceof Name name) {
        for (String m : marks) {
          if (name.getIdentifier().contains(m)) {
            out.add(finding(Finding.Kind.LIVE_SCRIPT, where,
                "hostile script has marker " + m + " as an identifier " + pos(text, n.getAbsolutePosition()),
                value));
          }
        }
      }
    }
  }

  /** Returns null when aligned, else a description of the first difference. */
  private static String align(
      Parsed bp, AstNode b, Parsed hp, AstNode h, Set<AstNode> slots, List<AstNode> hostileSlots) {
    if (slots.contains(b)) {
      if (!jsonLiteral(hp, h)) {
        return "slot value is " + describe(h) + ", not one literal";
      }
      hostileSlots.add(h);
      return null;
    }
    if (b.getClass() != h.getClass() || b.getType() != h.getType()) {
      return "hostile script has " + describe(h) + " where the benign one has " + describe(b);
    }
    List<AstNode> bc = bp.children.getOrDefault(b, List.of());
    List<AstNode> hc = hp.children.getOrDefault(h, List.of());
    if (bc.isEmpty() && hc.isEmpty() && !b.toSource().equals(h.toSource())) {
      return "hostile script has " + describe(h) + " where the benign one has " + describe(b);
    }
    if (bc.size() != hc.size()) {
      return "hostile " + describe(h) + " has " + hc.size() + " children, the benign one " + bc.size();
    }
    for (int i = 0; i < bc.size(); i++) {
      String d = align(bp, bc.get(i), hp, hc.get(i), slots, hostileSlots);
      if (d != null) {
        return d;
      }
    }
    return null;
  }

  /** String, number, true/false/null, or an array/object of those with string keys (JSON). */
  private static boolean jsonLiteral(Parsed p, AstNode n) {
    if (n instanceof StringLiteral || n instanceof NumberLiteral) {
      return true;
    }
    if (n instanceof KeywordLiteral k) {
      return k.getType() == Token.TRUE || k.getType() == Token.FALSE || k.getType() == Token.NULL;
    }
    if (n instanceof UnaryExpression u) {
      return u.getType() == Token.NEG && u.getOperand() instanceof NumberLiteral;
    }
    if (n instanceof ArrayLiteral a) {
      return a.getElements().stream().allMatch(e -> jsonLiteral(p, e));
    }
    if (n instanceof ObjectLiteral o) {
      for (AstNode c : p.children.getOrDefault(o, List.of())) {
        if (!(c instanceof ObjectProperty prop)
            || prop.getType() != Token.COLON
            || !(prop.getLeft() instanceof StringLiteral)
            || !jsonLiteral(p, prop.getRight())) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  // ---- helpers ----

  /** The text with each node's source span removed, the spans replaced by one separator. */
  private static String cut(String text, List<AstNode> spans) {
    List<AstNode> sorted = new ArrayList<>(spans);
    sorted.sort(java.util.Comparator.comparingInt(AstNode::getAbsolutePosition));
    StringBuilder sb = new StringBuilder();
    int at = 0;
    for (AstNode n : sorted) {
      int start = n.getAbsolutePosition();
      if (start < at) {
        return "\u0000overlap";
      }
      sb.append(text, at, start).append('\u0000');
      at = start + n.getLength();
    }
    return sb.append(text, Math.min(at, text.length()), text.length()).toString();
  }

  private static boolean inside(List<AstNode> spans, int start, int len) {
    for (AstNode n : spans) {
      int s = n.getAbsolutePosition();
      if (start >= s && start + len <= s + n.getLength()) {
        return true;
      }
    }
    return false;
  }

  /** The smallest node covering the range, or null (a comment or between tokens). */
  private static AstNode innermost(Parsed p, int start, int len) {
    AstNode best = null;
    for (AstNode n : p.nodes) {
      int s = n.getAbsolutePosition();
      if (n != p.root && start >= s && start + len <= s + n.getLength()
          && (best == null || n.getLength() <= best.getLength())) {
        best = n;
      }
    }
    return best;
  }

  /** The marker whose occurrence (with a character of slack either side) contains {@code offset}. */
  private static String markerAt(String text, Set<String> marks, int offset) {
    if (offset < 0) {
      return null;
    }
    for (String m : marks) {
      for (int i = text.indexOf(m); i >= 0; i = text.indexOf(m, i + 1)) {
        if (offset >= i - 1 && offset <= i + m.length()) {
          return m;
        }
      }
    }
    return null;
  }

  private static String describe(AstNode n) {
    String src = n.toSource();
    if (src.length() > 40) {
      src = src.substring(0, 40) + "...";
    }
    return n.getClass().getSimpleName() + " `" + src.replace("\n", "\\n") + "`";
  }

  private static String pos(String text, int offset) {
    if (offset < 0 || offset > text.length()) {
      return "";
    }
    int line = 1;
    int col = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
        col = 1;
      } else {
        col++;
      }
    }
    return "at script line " + line + " column " + col;
  }

  private static Finding finding(Finding.Kind kind, String where, String detail, String value) {
    return new Finding(kind, ENGINE, (where.isEmpty() ? "" : where + ": ") + detail.strip(), value);
  }

  private static List<Finding> dedupe(List<Finding> in) {
    return List.copyOf(new LinkedHashSet<>(in));
  }
}
