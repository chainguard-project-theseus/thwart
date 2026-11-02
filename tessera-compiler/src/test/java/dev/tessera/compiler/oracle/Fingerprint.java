package dev.tessera.compiler.oracle;

import dev.tessera.compiler.model.OutputContext;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.StringOutput;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fingerprint values the context check (#323) renders a case with, and the table that tells,
 * from what a slot's value looks like in the parsed document, which escaper the compiler chose.
 *
 * <p>Slot {@code n} gets {@link #value(int) value(n)}: the marker {@code tqf0001} (four digits) and
 * then {@code <'"&{{/}, a backtick and a newline, characters the escapers treat differently. The
 * table is built by calling the {@link Escapers} method each {@link OutputContext} names, so it
 * follows escaper changes without edits here. For each context it holds the escaped value from the
 * marker on (the <em>core</em>: a JSON string's quotes are not part of it, so a value concatenated
 * with other text still classifies) in two forms: as written ({@linkplain #classify read where the
 * parser doesn't decode}, raw text such as {@code <script>}) and after the parser decodes character
 * references (element text, RCDATA, attribute values, foreign {@code <script>}).
 *
 * <p>Some escapers write the fingerprint identically, and no fingerprint can tell them apart:
 * {@code text}, {@code attr}, {@code url} and {@code resourceUrl} entity-encode
 * the same characters and differ only in the scheme check, which a schemeless value passes (a value
 * with a disallowed scheme is replaced whole, so its slot couldn't be located). Decoded, the entity family also reads exactly like
 * the unescaped fingerprint, so {@link ContextCheck} classifies the render's bytes as well as the
 * parsed tree. {@link #classify} returns all matching contexts; identical bytes
 * are correct exactly where any of their contexts is, so the check accepts the union. A scheme check
 * the compiler forgot is #318's {@code JS_URL} check, with hostile values.
 *
 * <p>A slot can also reach the output through a link expression ({@code @{/x/{id}(id=${s})}},
 * {@code @{/x(q=${s})}}), which percent-encodes it as a path segment or a query value before the
 * context's escaper runs. The table also holds those <em>link cores</em>, built with
 * {@link DefaultLinks}, for every context including {@link OutputContext#REFRESH} (whose escaper
 * only takes {@code <digits>;url=<path>}, so it only ever sees a slot inside a link). A path segment
 * encodes every character the escapers touch, so its core is the same in every context and is
 * accepted wherever some escaper is correct: the value is {@code [A-Za-z0-9._~%-]} only, inert
 * everywhere. A query value keeps {@code '} and {@code /}, which still tell the entity family from
 * JSON and from REFRESH.
 *
 * <p>Contexts without an escaper are build errors and have no fingerprint.
 */
public final class Fingerprint {

  /** Marker prefix; distinct from #318's benign marker {@code tqm}. */
  public static final String PREFIX = "tqf";

  /** What follows the marker: characters every escaper writes differently. */
  static final String TAIL = "<'\"&{{/`\n";

  /** A fingerprint marker anywhere in a render. */
  static final Pattern MARKER = Pattern.compile(PREFIX + "[0-9]{4}");

  private static final Set<OutputContext> NOT_FINGERPRINTED = EnumSet.of(OutputContext.REFRESH);

  /** A character reference, as the escapers write them. */
  private static final Pattern REFERENCE = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);");

  /** Core of each context's escaped fingerprint, as written. */
  private static final Map<OutputContext, String> RAW = new EnumMap<>(OutputContext.class);

  /** The same after character-reference decoding. */
  private static final Map<OutputContext, String> DECODED = new EnumMap<>(OutputContext.class);

  /** Cores of each context's escaped fingerprint inside a link (path segment, query value), as written. */
  private static final Map<OutputContext, List<String>> LINK_RAW = new EnumMap<>(OutputContext.class);

  /** The same after character-reference decoding. */
  private static final Map<OutputContext, List<String>> LINK_DECODED = new EnumMap<>(OutputContext.class);

  static {
    DefaultLinks links = new DefaultLinks("");
    String marker = marker(1);
    List<String> linkValues =
        List.of(
            links.build("/{p}", new String[] {"p"}, new Object[] {value(1)}),
            links.build("/", new String[] {"q"}, new Object[] {value(1)}));
    for (OutputContext c : OutputContext.values()) {
      if (c.escaper() == null) {
        continue;
      }
      if (!NOT_FINGERPRINTED.contains(c)) {
        String raw = core(c, "", value(1));
        RAW.put(c, raw);
        DECODED.put(c, decode(raw));
      }
      List<String> raw = new ArrayList<>();
      List<String> decoded = new ArrayList<>();
      for (String link : linkValues) {
        int at = link.indexOf(marker);
        String prefix = (c == OutputContext.REFRESH ? "0;url=" : "") + link.substring(0, at);
        String core = core(c, prefix, link.substring(at));
        raw.add(core);
        decoded.add(decode(core));
      }
      LINK_RAW.put(c, raw);
      LINK_DECODED.put(c, decoded);
    }
  }

  private Fingerprint() {}

  /** The marker of slot {@code slot} (1 to 9999), e.g. {@code tqf0001}. */
  public static String marker(int slot) {
    if (slot < 1 || slot > 9999) {
      throw new IllegalArgumentException("slot out of range: " + slot);
    }
    return String.format("%s%04d", PREFIX, slot);
  }

  /** The value to render slot {@code slot} with: its marker followed by {@link #TAIL}. */
  public static String value(int slot) {
    return marker(slot) + TAIL;
  }

  /** The contexts in the table. */
  public static Set<OutputContext> contexts() {
    return Collections.unmodifiableSet(RAW.keySet());
  }

  /** The core of {@code context}'s escaped fingerprint as written ({@code decoded} false) or decoded. */
  static String core(OutputContext context, boolean decoded) {
    return (decoded ? DECODED : RAW).get(context);
  }

  /** The link cores of {@code context}: path segment, then query value. */
  static List<String> linkCores(OutputContext context, boolean decoded) {
    return (decoded ? LINK_DECODED : LINK_RAW).get(context);
  }

  /**
   * The contexts whose escaper writes a fingerprint that reads as {@code text} does from
   * {@code at}, where a marker starts. {@code decoded} says whether the parser decoded character
   * references at that location. Empty if no escaper writes it that way (fail closed: the caller
   * reports an unclassified escaper).
   */
  public static Set<OutputContext> classify(String text, int at, boolean decoded) {
    Set<OutputContext> found = EnumSet.noneOf(OutputContext.class);
    for (Map.Entry<OutputContext, String> e : (decoded ? DECODED : RAW).entrySet()) {
      if (text.startsWith(e.getValue(), at)) {
        found.add(e.getKey());
      }
    }
    for (Map.Entry<OutputContext, List<String>> e : (decoded ? LINK_DECODED : LINK_RAW).entrySet()) {
      for (String core : e.getValue()) {
        if (text.startsWith(core, at)) {
          found.add(e.getKey());
        }
      }
    }
    return found;
  }

  /**
   * What {@code c}'s escaper writes for {@code tail} (which starts with the marker) after
   * {@code prefix}, without what it writes for {@code prefix} alone (a JSON string's quotes).
   */
  private static String core(OutputContext c, String prefix, String tail) {
    String out = escape(c, prefix + tail);
    String alone = escape(c, prefix);
    int p = out.indexOf(marker(1));
    if (p < 0) {
      throw new IllegalStateException(c + " escaper loses the fingerprint marker: " + out);
    }
    if (p > alone.length() || !out.startsWith(alone.substring(0, p)) || !out.endsWith(alone.substring(p))) {
      throw new IllegalStateException(c + " escaper doesn't wrap values uniformly: " + out);
    }
    return out.substring(p, out.length() - (alone.length() - p));
  }

  /** Calls the {@link Escapers} method {@code c} names (test code: reflection is fine here). */
  private static String escape(OutputContext c, String value) {
    try {
      Method m = Escapers.class.getMethod(c.escaper(), Output.class, Object.class);
      StringOutput out = new StringOutput();
      m.invoke(null, out, value);
      return out.toString();
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(c + " escaper rejects the fingerprint", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("no Escapers." + c.escaper() + "(Output, Object) for " + c, e);
    }
  }

  /**
   * Decodes the character references the escapers write. Fails on any other named reference, so a
   * new one in an escaper shows here instead of misclassifying.
   */
  static String decode(String s) {
    Matcher m = REFERENCE.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String ref = m.group(1);
      String ch =
          switch (ref) {
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> {
              if (ref.startsWith("#x") || ref.startsWith("#X")) {
                yield Character.toString(Integer.parseInt(ref.substring(2), 16));
              }
              if (ref.startsWith("#")) {
                yield Character.toString(Integer.parseInt(ref.substring(1)));
              }
              throw new IllegalStateException("Fingerprint.decode doesn't know &" + ref + ";");
            }
          };
      m.appendReplacement(sb, Matcher.quoteReplacement(ch));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
