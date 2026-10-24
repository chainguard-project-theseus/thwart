package dev.tessera.compiler.oracle;

import dev.tessera.compiler.model.OutputContext;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Output;
import dev.tessera.runtime.StringOutput;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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
 * {@code rcdata}, {@code attr}, {@code url}, {@code urlList} and {@code resourceUrl} entity-encode
 * the same characters and differ only in the scheme check, which a schemeless value passes (a value
 * with a disallowed scheme is replaced whole, so its slot couldn't be located); and {@code json} and
 * {@code jsonAttr} decode to the same string. Decoded, the entity family also reads exactly like
 * the unescaped fingerprint, so {@link ContextCheck} classifies the render's bytes as well as the
 * parsed tree. {@link #classify} returns all matching contexts; identical bytes
 * are correct exactly where any of their contexts is, so the check accepts the union. A scheme check
 * the compiler forgot is #318's {@code JS_URL} check, with hostile values.
 *
 * <p>{@link OutputContext#REFRESH} is not in the table: its escaper only accepts
 * {@code <digits>;url=<same-origin path>}, and the compiler only lets a literal link expression there,
 * never a slot value. Contexts without an escaper are build errors and have no fingerprint.
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

  static {
    for (OutputContext c : OutputContext.values()) {
      if (c.escaper() == null || NOT_FINGERPRINTED.contains(c)) {
        continue;
      }
      String raw = core(c);
      RAW.put(c, raw);
      DECODED.put(c, decode(raw));
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
    return found;
  }

  /** The escaped fingerprint of slot 1 from the marker on, without what the escaper writes for "". */
  private static String core(OutputContext c) {
    String marker = marker(1);
    String out = escape(c, value(1));
    String empty = escape(c, "");
    int p = out.indexOf(marker);
    if (p < 0) {
      throw new IllegalStateException(c + " escaper loses the fingerprint marker: " + out);
    }
    int trailing = empty.length() - p;
    if (trailing < 0
        || !empty.equals(out.substring(0, p) + out.substring(out.length() - trailing))) {
      throw new IllegalStateException(c + " escaper doesn't wrap values uniformly: " + out);
    }
    return out.substring(p, out.length() - trailing);
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
