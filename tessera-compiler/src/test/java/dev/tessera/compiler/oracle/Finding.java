package dev.tessera.compiler.oracle;

import java.util.Objects;

/**
 * Something an oracle check found in a render. The HTML checks (#318), the script check (#319) and
 * the context check (#323) produce these; the gate (#324) ratchets them against {@link KnownHoles}.
 *
 * @param kind what was found
 * @param engine the parser that saw it, exactly as named in {@code known-holes.txt}: {@code vnu-1.4},
 *     {@code vnu-26}, {@code rhino}, or a Node engine ({@code parse5}, {@code chromium},
 *     {@code firefox}, {@code webkit}, #322). No flags here, or known-holes lines wouldn't match
 * @param where where it was seen: the scripting flag, if the check depends on it, then a node path,
 *     e.g. {@code scripting-off /html/body/div/svg[svg]/title[svg]}
 * @param value the hostile value that caused it
 */
public record Finding(Kind kind, String engine, String where, String value) {

  /** The kinds of finding. Names are used verbatim in {@code known-holes.txt}. */
  public enum Kind {
    /** The hostile tree's structure differs from the benign tree's (#318). */
    STRUCTURE_CHANGED,
    /** A marker appears in an element or attribute name (#318). */
    MARKER_IN_NAME,
    /** A marker is executable script (#318, #319). */
    LIVE_SCRIPT,
    /** A marker is in an event-handler attribute (#318). */
    LIVE_HANDLER,
    /** A marker is in a {@code javascript:}, {@code vbscript:} or {@code data:} URL (#318). */
    JS_URL,
    /** A resource URL is protocol-relative or absolute by shape (#318). */
    CROSS_ORIGIN_RESOURCE,
    /** A script slot's value is not exactly one literal in expression position (#319). */
    JS_NOT_LITERAL,
    /** Rhino can't parse the benign script: unchecked, counted, not a failure (#319). */
    JS_UNPARSABLE,
    /** The escaper the compiler chose doesn't match where the parser put the value (#323). */
    CONTEXT_MISMATCH,
    /** A marker inside {@code {{ ... }}} in text a client framework would interpolate (#318). */
    CSTI
  }

  public Finding {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(engine, "engine");
    Objects.requireNonNull(where, "where");
    Objects.requireNonNull(value, "value");
  }
}
