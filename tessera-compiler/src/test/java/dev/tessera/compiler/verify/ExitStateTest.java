package dev.tessera.compiler.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.Skeleton.Escaper;
import dev.tessera.compiler.verify.Skeleton.Node;
import dev.tessera.compiler.verify.Skeleton.Seq;
import dev.tessera.compiler.verify.engine.EngineException;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.Parser;
import dev.tessera.compiler.verify.engine.ParserState;
import dev.tessera.compiler.verify.engine.Vnu;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A bounded exhaustive search over the verifier's control flow (#752): {@code Opt}, {@code Alt},
 * {@code Rep} (with each separator), {@code Call} and computed attributes around bodies of one or
 * two items, in each wrapper. For every skeleton the verifier accepts, every render (each branch,
 * zero, one and two passes) must
 *
 * <ul>
 *   <li>(A) be accepted as straight-line markup: error-free with scripting on and off, each hole in
 *       a state its rule accepts, ending balanced;
 *   <li>(C) end in the exit state the verifier went on from, in every context it parsed in. The one
 *       allowance is template mode, where the verifier may go on from the "in body" state a flow
 *       start tag switches to ({@link ContentKinds#switchesMode}), which is stricter.
 * </ul>
 *
 * With #708's fix reverted, (C) finds {@code <template>} around a loop separated by {@code <br />}.
 */
class ExitStateTest {
  private static final SourcePos POS = new SourcePos("t.html", 1, 1);
  private static final String[][] WRAPPERS = {{"", ""}, {"<table><tr><td>", "</td></tr></table>"},
      {"<template>", "</template>"}, {"<select><option>", "</option></select>"}, {"<p>", "</p>"},
      {"<ul><li>", "</li></ul>"}, {"<button>", "</button>"}};
  private static final String[] SEPARATORS = {"", "\n", "<br />", "<p>"};
  private static final List<Node> ITEMS = new ArrayList<>();

  static {
    for (String s : new String[] {"<!--", "-->", "<script>", "</script>", "<style>", "</style>", "<textarea>",
        "</textarea>", "<title>", "</title>", "<noscript>", "</noscript>", "<template>", "</template>", "<table>",
        "</table>", "<select>", "</select>", "<p>", "</p>", "<b>", "</b>", "<br />", "<b title=\"", "\">", "<li>",
        "</li>", "x"}) {
      ITEMS.add(st(s));
    }
    ITEMS.add(new Skeleton.Hole(Escaper.TEXT, null, POS));
    ITEMS.add(new Skeleton.RawInsert(Skeleton.Source.SAFE_HTML, POS));
  }

  private final List<String> findings = new ArrayList<>();
  private int cases;
  private int accepted;

  @Test
  void everyRenderOfAnAcceptedSkeletonEndsInTheVerifiersExitState() throws EngineException {
    List<Seq> bodies = new ArrayList<>();
    for (Node a : ITEMS) {
      bodies.add(seq(a));
      for (Node b : ITEMS) {
        bodies.add(seq(a, b));
      }
    }
    for (String[] w : WRAPPERS) {
      for (Seq body : bodies) {
        for (int r = 0; r < SEPARATORS.length + 3; r++) {
          Map<String, Seq> frags = new HashMap<>();
          run(w, region(r, body, frags), frags);
        }
      }
      // Depth 2 for single-item bodies.
      for (Node item : ITEMS) {
        for (int inner = 0; inner < SEPARATORS.length + 3; inner++) {
          for (int outer = 0; outer < SEPARATORS.length + 3; outer++) {
            Map<String, Seq> frags = new HashMap<>();
            run(w, region(outer, seq(region(inner, seq(item), frags)), frags), frags);
          }
        }
      }
      // A computed attribute in a start tag, written or not.
      for (Seq attr : List.of(seq(st(" title=\""), new Skeleton.Hole(Escaper.ATTR, "title", POS), st("\"")),
          seq(st(" disabled")))) {
        for (String before : new String[] {"<b", "<b ", "<b a", "<b a=\"v\""}) {
          for (String after : new String[] {">x</b>", " >x</b>", "/>", " c>x</b>", "x>x</b>", "=\"v\">x</b>"}) {
            run(w, seq(st(before), new Skeleton.Opt(Skeleton.OptKind.ATTRIBUTE, attr, POS), st(after)), Map.of());
          }
        }
      }
    }
    assertEquals(List.of(), findings.subList(0, Math.min(20, findings.size())),
        findings.size() + " of " + accepted + " accepted skeletons (of " + cases + ")");
    // Not vacuous: the verifier accepts a fair share of the cases.
    assertTrue(accepted > cases / 20, accepted + " of " + cases);
  }

  /** Region {@code r} around {@code body}: t:if, a choice of it or nothing, a loop with each separator, a call. */
  private static Node region(int r, Seq body, Map<String, Seq> frags) {
    switch (r) {
      case 0:
        return new Skeleton.Opt(Skeleton.OptKind.CONDITION, body, POS);
      case 1:
        return new Skeleton.Alt(List.of(body, Seq.empty()), POS);
      case 2:
        String name = "f" + frags.size();
        frags.put(name, body);
        return new Skeleton.Call(new Skeleton.FragmentId("t", name), Map.of(), POS);
      default:
        return new Skeleton.Rep(Skeleton.RepKind.EACH, body, seq(st(SEPARATORS[r - 3])), POS);
    }
  }

  private void run(String[] wrapper, Node region, Map<String, Seq> frags) throws EngineException {
    cases++;
    Seq whole = seq(st(wrapper[0]), region, st(wrapper[1]));
    if (!check(whole, frags, null).isEmpty()) {
      return;
    }
    accepted++;
    // (C) compares the state right after the region: the wrapper's closer would reset most of it.
    Map<OpenElement, ParserState[]> exits = new LinkedHashMap<>();
    check(seq(st(wrapper[0]), region), frags, exits);
    for (List<Node> render : expand(whole.nodes(), frags)) {
      List<Finding> a = check(new Seq(render), Map.of(), null);
      if (!a.isEmpty()) {
        findings.add("A " + whole + " render " + new Seq(render) + ": " + VerifierMessages.message(a.get(0)));
      }
    }
    for (List<Node> render : expand(List.of(st(wrapper[0]), region), frags)) {
      String html = html(render);
      for (Map.Entry<OpenElement, ParserState[]> e : exits.entrySet()) {
        if (e.getKey() == null && exits.size() > 1) {
          // A partial as a document: balance is checked only in its fragment contexts (ContextVerifier.check), and
          // (A) checks its holes in the concrete states.
          continue;
        }
        ParserState[] want = e.getValue();
        for (int i = 0; i < want.length; i++) {
          ParserState got = parse(e.getKey(), i == 0, html);
          String w = ContentKinds.flowSignature(want[i]);
          if (!w.equals(ContentKinds.flowSignature(got)) && !(ContentKinds.switchesMode(got)
              && w.equals(ContentKinds.flowSignature(parse(e.getKey(), i == 0, html + ContentKinds.REPRESENTATIVE))))) {
            findings.add("C " + whole + " render " + html + " in " + (e.getKey() == null ? "document" : e.getKey().localName())
                + (i == 0 ? "" : " (scripting off)") + ": verifier " + want[i] + ", browser " + got);
          }
        }
      }
    }
  }

  /**
   * What the verifier rejects in {@code body}; with {@code exits}, records the exit states and drops the partial's
   * own balance (the body is cut after the region).
   */
  private static List<Finding> check(Seq body, Map<String, Seq> frags, Map<OpenElement, ParserState[]> exits) {
    Skeleton.TemplateSkeleton t = new Skeleton.TemplateSkeleton("t", body, frags);
    ContextVerifier v = new ContextVerifier(Map.of("t", t));
    if (exits != null) {
      v.exits = exits::put;
    }
    return v.check(t);
  }

  /** Every straight-line render of {@code nodes}: each branch; zero, one and two passes. */
  private static List<List<Node>> expand(List<Node> nodes, Map<String, Seq> frags) {
    List<List<Node>> out = List.of(List.of());
    for (Node n : nodes) {
      List<List<Node>> options = new ArrayList<>();
      if (n instanceof Skeleton.Opt o) {
        options.add(List.of());
        options.addAll(expand(o.body().nodes(), frags));
      } else if (n instanceof Skeleton.Alt alt) {
        for (Seq b : alt.branches()) {
          options.addAll(expand(b.nodes(), frags));
        }
      } else if (n instanceof Skeleton.Rep r) {
        options.add(List.of());
        options.addAll(expand(r.body().nodes(), frags));
        List<Node> twice = new ArrayList<>(r.body().nodes());
        twice.addAll(r.separator().nodes());
        twice.addAll(r.body().nodes());
        options.addAll(expand(twice, frags));
      } else if (n instanceof Skeleton.Call c) {
        options.addAll(expand(frags.get(c.target().name()).nodes(), frags));
      } else if (n instanceof Seq s) {
        options.addAll(expand(s.nodes(), frags));
      } else {
        options.add(List.of(n));
      }
      List<List<Node>> next = new ArrayList<>();
      for (List<Node> prefix : out) {
        for (List<Node> o : options) {
          List<Node> l = new ArrayList<>(prefix);
          l.addAll(o);
          next.add(l);
        }
      }
      out = next;
    }
    return out;
  }

  /** The markup of a render: a value for each hole; a raw value as nothing (template mode: the allowance). */
  private static String html(List<Node> render) {
    StringBuilder sb = new StringBuilder();
    for (Node n : render) {
      if (n instanceof Skeleton.Static s) {
        sb.append(s.text());
      } else if (n instanceof Skeleton.Hole) {
        sb.append('x');
      }
    }
    return sb.toString();
  }

  private static ParserState parse(OpenElement context, boolean scripting, String html) throws EngineException {
    Parser p = context == null ? Vnu.document(scripting) : Vnu.fragment(context.namespace(), context.localName(), scripting);
    p.feed(html);
    return p.state();
  }

  private static Skeleton.Static st(String s) {
    return new Skeleton.Static(s, POS);
  }

  private static Seq seq(Node... nodes) {
    return new Seq(List.of(nodes));
  }
}
