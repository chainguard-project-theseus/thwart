package dev.tessera.compiler.verify;

import dev.tessera.compiler.Backports;
import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.engine.ParserState;
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
  /** How many parsers the path had (both engines, scripting on and maybe off). */
  final int parsers;
  /** Extra words for the message (engine failure text), or null. */
  final String detail;
  /** Reached only by a fragment calling itself. */
  final boolean recursive;

  Finding(Problem problem, SourcePos at, SourcePos inner, Skeleton.FragmentId fragment, Skeleton.Escaper escaper,
      Skeleton.Source source, String attribute, List<ParserState> states, int parsers, String detail,
      boolean recursive) {
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
  }

  /** This finding seen from a call of {@code target} at {@code callSite}: reported there. */
  Finding via(SourcePos callSite, Skeleton.FragmentId target) {
    return new Finding(problem, callSite, inner != null ? inner : at, fragment != null ? fragment : target,
        escaper, source, attribute, states, parsers, detail, recursive);
  }

  /** The key two findings are the same error by. */
  String key() {
    return problem + " " + at + " " + inner;
  }
}
