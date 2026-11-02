package dev.tessera.compiler.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code BigDecimal} and {@code BigInteger} are not final, so an application subclass can override
 * {@code toString()}, for example to return a label. JSON output ({@code [[...]]} in a
 * {@code <script>}) writes a number's text as is, a {@code data-*} attribute writes it in plain decimal, and {@link MessageFormat}
 * formats these two types from their text, so a subclass whose text is not a JSON number must fail
 * the render instead of reaching the page. So must one in a {@code style} attribute, where CSS cannot
 * be escaped. Renders here go through the generated registry, as in
 * production, without the test harness's skeleton check.
 */
class BigNumberSubclassTest {
  private static final String HOSTILE = "0;alert(1)//</script><script>alert(2)</script>";

  /** An application subclass of BigDecimal whose toString() returns its label. */
  static final class Price extends BigDecimal {
    private static final long serialVersionUID = 1L;
    private final String label;

    Price(String label) {
      super(1);
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** An application subclass of BigInteger whose toString() returns its label. */
  static final class Count extends BigInteger {
    private static final long serialVersionUID = 1L;
    private final String label;

    Count(String label) {
      super("1");
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private static final String PARAMS = "java.math.BigDecimal x, java.util.List<java.math.BigInteger> y";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"" + PARAMS + "\">" + body + "</div>")
        .messages("price=Now <b>{0}</b>\n")
        .compile()
        .assertSuccess();
  }

  /** The production render path: the generated registry's renderer, with no skeleton check. */
  private static String render(Compilation c, Object x, Object y) {
    StringOutput out = new StringOutput();
    c.registry().find("page").orElseThrow().renderer().render(Map.of("x", x, "y", y), ctx(), out);
    return out.toString();
  }

  /** Like Spring: MessageFormat only when there are arguments. */
  private static RenderContext ctx() {
    return new RenderContext(
        Locale.ROOT,
        (k, a) -> a.length == 0 ? null : new MessageFormat("Now <b>{0}</b>", Locale.ROOT).format(a),
        new DefaultLinks(""),
        Forms.NONE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"<script>var a = [[${x}]];</script>", "<p t:data-v=\"${x}\">p</p>"})
  void bigDecimalSubclassWithHostileTextFailsTheRender(String body) {
    Compilation c = compile(body);
    assertThrows(IllegalArgumentException.class, () -> render(c, new Price(HOSTILE), List.of()));
    assertThrows(IllegalArgumentException.class, () -> render(c, new Price("1-2"), List.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"<script>var a = [[${y}]];</script>"})
  void bigIntegerSubclassWithHostileTextFailsTheRender(String body) {
    Compilation c = compile(body);
    assertThrows(
        IllegalArgumentException.class, () -> render(c, BigDecimal.ONE, List.of(BigInteger.TWO, new Count(HOSTILE))));
    assertThrows(IllegalArgumentException.class, () -> render(c, BigDecimal.ONE, List.of(new Count("1-2"))));
  }

  @Test
  void bigDecimalSubclassWithHostileTextFailsAnHtmlMessage() {
    Compilation c = compile("<p t:utext=\"#{price(x)}\">x</p>");
    assertThrows(IllegalArgumentException.class, () -> render(c, new Price("<b>"), List.of()));
    assertEquals("<div><p>Now <b>1,234</b></p></div>", render(c, new BigDecimal("1234"), List.of()));
    // Only the JDK class itself: a subclass fails even when its text is a number.
    assertThrows(IllegalArgumentException.class, () -> render(c, new Price("1"), List.of()));
  }

  @Test
  void jdkValuesRenderUnchanged() {
    Compilation c =
        compile("<script>var a = [[${x}]]; var b = [[${y}]];</script><p t:data-v=\"${x}\">p</p>");
    List<BigInteger> y = List.of(new BigInteger("-123456789012345678901234567890"), BigInteger.ZERO);
    String want =
        "<div><script>var a = (1E+3); var b = ([-123456789012345678901234567890,0]);</script>"
            + "<p data-v=\"1000\">p</p></div>";
    assertEquals(want, render(c, new BigDecimal("1E+3"), y));
    assertEquals(want, c.render("page", Map.of("x", new BigDecimal("1E+3"), "y", y)));
    assertEquals(
        "<div><script>var a = (-0.000012345); var b = ([]);</script><p data-v=\"-0.000012345\">p</p></div>",
        render(c, new BigDecimal(BigInteger.valueOf(-12345), 9), List.of()));
    // Only the JDK classes themselves: a subclass fails even when its text is a JSON number.
    assertThrows(IllegalArgumentException.class, () -> render(c, new Price("2.50"), List.of()));
    assertThrows(IllegalArgumentException.class, () -> render(c, BigDecimal.ONE, List.of(new Count("7"))));
  }

  // style attributes (#182): CSS cannot be escaped, so a number's text must be a number.

  private static final String CSS_HOSTILE = "1;background-image:url(https://evil/x)";

  /** Style values with BigDecimal x and d and BigInteger z in every position styleSafe allows. */
  static final List<String> STYLES =
      List.of(
          "<p t:style=\"x\">p</p>",
          "<p t:style=\"|width: ${x}px|\">p</p>",
          "<p t:style=\"'width: ' + x + 'px'\">p</p>",
          "<p t:style=\"|width: ${x != null ? x : d}px|\">p</p>",
          "<p t:style=\"|width: ${x ?: d}px|\">p</p>",
          "<p t:style=\"|width: ${x}px; height: ${z}px|\">p</p>",
          "<p t:style=\"|width: ${x}px|\">p</p>");

  private static String renderStyle(String body, Object x, Object z) {
    return renderStyle(body, x, z, BigDecimal.ONE);
  }

  private static String renderStyle(String body, Object x, Object z, Object d) {
    Compilation c =
        Compilation.builder()
            .template("page.html", "<div t:params=\"java.math.BigDecimal x, java.math.BigInteger z, java.math.BigDecimal d\">" + body + "</div>")
            .compile()
            .assertSuccess();
    Map<String, Object> model = new HashMap<>();
    model.put("x", x);
    model.put("z", z);
    model.put("d", d);
    StringOutput out = new StringOutput();
    c.registry().find("page").orElseThrow().renderer().render(model, RenderContext.defaults(), out);
    return out.toString();
  }

  @ParameterizedTest
  @FieldSource("STYLES")
  void bigDecimalSubclassWithHostileTextFailsAStyle(String body) {
    assertThrows(IllegalArgumentException.class, () -> renderStyle(body, new Price(CSS_HOSTILE), BigInteger.ONE));
    assertThrows(IllegalArgumentException.class, () -> renderStyle(body, new Price("1-2"), BigInteger.ONE));
  }

  @Test
  void bigIntegerSubclassWithHostileTextFailsAStyle() {
    assertThrows(
        IllegalArgumentException.class,
        () -> renderStyle("<p t:style=\"|height: ${z}px|\">p</p>", BigDecimal.ONE, new Count(CSS_HOSTILE)));
  }

  @Test
  void jdkValuesRenderUnchangedInAStyle() {
    BigDecimal x = new BigDecimal("12.5");
    BigInteger z = new BigInteger("123456789012345678901234567890");
    assertEquals("<div><p style=\"12.5\">p</p></div>", renderStyle(STYLES.get(0), x, z));
    for (String body : STYLES.subList(1, 5)) {
      assertEquals("<div><p style=\"width: 12.5px\">p</p></div>", renderStyle(body, x, z), body);
    }
    assertEquals(
        "<div><p style=\"width: 12.5px; height: 123456789012345678901234567890px\">p</p></div>",
        renderStyle(STYLES.get(5), x, z));
    assertEquals("<div><p style=\"width: 12.5px\">p</p></div>", renderStyle(STYLES.get(6), x, z));
    assertEquals("<div><p style=\"width: 1E+3px\">p</p></div>", renderStyle(STYLES.get(1), new BigDecimal("1E+3"), z));
    // Only the JDK class itself: a subclass fails even when its text is a number.
    assertThrows(IllegalArgumentException.class, () -> renderStyle(STYLES.get(1), new Price("2.50"), z));
    // null behaves as before: the attribute is omitted, the default is used, or "null" is printed.
    assertEquals("<div><p>p</p></div>", renderStyle(STYLES.get(0), null, z));
    assertEquals("<div><p style=\"width: 1px\">p</p></div>", renderStyle(STYLES.get(3), null, z));
    assertEquals("<div><p style=\"width: 1px\">p</p></div>", renderStyle(STYLES.get(4), null, z));
    assertEquals("<div><p style=\"width: nullpx\">p</p></div>", renderStyle(STYLES.get(1), null, z));
  }

  /** The hostile value in the branch or fallback that is output, at the top level and inside |...|. */
  static final List<String> BRANCH_STYLES =
      List.of(
          "<p t:style=\"x != null ? d : x\">p</p>",
          "<p t:style=\"x == null ? x : d\">p</p>",
          "<p t:style=\"x ?: d\">p</p>",
          "<p t:style=\"x != null ? d\">p</p>",
          "<p t:style=\"|width: ${x == null ? x : d}px|\">p</p>",
          "<p t:style=\"|width: ${x ?: d}px|\">p</p>");

  @ParameterizedTest
  @FieldSource("BRANCH_STYLES")
  void hostileBranchOrFallbackFailsAStyle(String body) {
    Object x = body.contains("?:") ? null : BigDecimal.ONE;
    assertThrows(IllegalArgumentException.class, () -> renderStyle(body, x, BigInteger.ONE, new Price(CSS_HOSTILE)));
    assertEquals(
        body.contains("width") ? "<div><p style=\"width: 2px\">p</p></div>" : "<div><p style=\"2\">p</p></div>",
        renderStyle(body, x, BigInteger.ONE, new BigDecimal("2")));
  }

  @Test
  void bigIntegerSubclassWithHostileTextFailsAStyleConcatenation() {
    String body = "<p t:style=\"'height: ' + z + 'px'\">p</p>";
    assertThrows(IllegalArgumentException.class, () -> renderStyle(body, BigDecimal.ONE, new Count(CSS_HOSTILE)));
    assertEquals("<div><p style=\"height: 7px\">p</p></div>", renderStyle(body, BigDecimal.ONE, BigInteger.valueOf(7)));
  }
}
