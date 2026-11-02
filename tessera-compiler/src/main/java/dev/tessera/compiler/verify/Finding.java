package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.engine.OpenElement;
import dev.tessera.compiler.verify.engine.ParserState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One thing the verifier rejects: the problem, where the template is wrong ({@link #at}: the hole,
 * or for a hole inside a fragment, the outermost call site in the template being checked), and the
 * parser states that reject it. Immutable.
 */
final class Finding {
  final Problem problem;
  /** Where the error is reported. */
  final SourcePos at;
  /** The hole or node inside a fragment, when {@link #at} is a call site; else null. */
  final SourcePos inner;
  /** The fragment {@link #inner} is in, or null. */
  final Skeleton.FragmentId fragment;
  /** The escaper of a rejected hole, or null. */
  final Skeleton.Escaper escaper;
  /** What a raw insert is, or null. */
  final Skeleton.Source source;
  /** The attribute name the template writes, for attribute holes; else null. */
  final String attribute;
  /** The states that reject it (empty for problems that aren't about one state). */
  final List<ParserState> states;
  /** How many parsers the path had (scripting on, and maybe off). */
  final int parsers;
  /** Extra words for the message (engine failure text), or null. */
  final String detail;
  /** Reached only by a fragment calling itself. */
  final boolean recursive;
  /**
   * The element the first state reads the value in because of markup before it: the raw-text
   * element, the outermost {@code <svg>} or {@code <math>}, or the table part; else null.
   */
  final OpenElement cause;
  /** Where the start tag that opened {@link #cause} is, or null. */
  final SourcePos causeAt;
  /** The fragment calls from {@link #at} to {@link #inner}, the outermost first; empty outside fragments. */
  final List<Step> calls;
  /**
   * For {@link Problem#STRUCTURE_DEPENDS_ON_DATA}: the cycle of calls that recurses, each call with
   * its position; the last one calls the fragment the cycle starts in again. Empty if unknown.
   */
  final List<Step> cycle;

  /** A call of {@link #fragment} at {@link #at}. */
  static final class Step {
    final Skeleton.FragmentId fragment;
    final SourcePos at;

    Step(Skeleton.FragmentId fragment, SourcePos at) {
      this.fragment = fragment;
      this.at = at;
    }
  }

  Finding(Problem problem, SourcePos at, SourcePos inner, Skeleton.FragmentId fragment, Skeleton.Escaper escaper,
      Skeleton.Source source, String attribute, List<ParserState> states, int parsers, String detail,
      boolean recursive) {
    this(problem, at, inner, fragment, escaper, source, attribute, states, parsers, detail, recursive, null, null,
        Collections.<Step>emptyList(), Collections.<Step>emptyList());
  }

  private Finding(Problem problem, SourcePos at, SourcePos inner, Skeleton.FragmentId fragment,
      Skeleton.Escaper escaper, Skeleton.Source source, String attribute, List<ParserState> states, int parsers,
      String detail, boolean recursive, OpenElement cause, SourcePos causeAt, List<Step> calls, List<Step> cycle) {
    this.problem = problem;
    this.at = at;
    this.inner = inner;
    this.fragment = fragment;
    this.escaper = escaper;
    this.source = source;
    this.attribute = attribute;
    this.states = Backports.listCopy(states);
    this.parsers = parsers;
    this.detail = detail;
    this.recursive = recursive;
    this.cause = cause;
    this.causeAt = causeAt;
    this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
    this.cycle = Collections.unmodifiableList(new ArrayList<>(cycle));
  }

  /** This finding with the element {@code e}, opened at {@code openedAt}, as its {@link #cause}. */
  Finding causedBy(OpenElement e, SourcePos openedAt) {
    return new Finding(problem, at, inner, fragment, escaper, source, attribute, states, parsers, detail, recursive,
        e, openedAt, calls, cycle);
  }

  /** This finding with {@code steps} as its {@link #cycle}. */
  Finding withCycle(List<Step> steps) {
    return new Finding(problem, at, inner, fragment, escaper, source, attribute, states, parsers, detail, recursive,
        cause, causeAt, calls, steps);
  }

  /** This finding seen from a call of {@code target} at {@code callSite}: reported there. */
  Finding via(SourcePos callSite, Skeleton.FragmentId target) {
    List<Step> chain = new ArrayList<>();
    chain.add(new Step(target, callSite));
    chain.addAll(calls);
    return new Finding(problem, callSite, inner != null ? inner : at, fragment != null ? fragment : target,
        escaper, source, attribute, states, parsers, detail, recursive, cause, causeAt, chain, cycle);
  }

  /** The key two findings are the same error by. */
  String key() {
    return problem + " " + at + " " + inner;
  }
}
