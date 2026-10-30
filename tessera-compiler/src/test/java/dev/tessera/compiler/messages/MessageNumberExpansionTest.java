package dev.tessera.compiler.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import dev.tessera.runtime.TemplateEntry;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * {@link MessageFormat} writes a number in plain notation, so {@code 1E+999999999} would format to
 * more than a billion characters. Every form of a message with arguments fails the render for a
 * {@code BigDecimal} with more than 10,000 integer digits instead. Renders go through the generated
 * registry, as in production, without the test harness's skeleton check.
 */
class MessageNumberExpansionTest {
  private static final String BUNDLE = "total=Total <b>{0}</b>\n";

  /** Every place a message #{...} with an argument can be written. */
  static final List<String> FORMS =
      List.of(
          "<p t:text=\"#{total(x)}\">p</p>",
          "<p>[[#{total(x)}]]</p>",
          "<p t:title=\"#{total(x)}\">p</p>",
          "<script>var t = [[#{total(x)}]];</script>",
          "<p t:utext=\"#{total(x)}\">p</p>",
          "<p>[(#{total(x)})]</p>");

  /** Like Spring: MessageFormat only when there are arguments. */
  private static RenderContext ctx() {
    return new RenderContext(
        Locale.ROOT,
        (k, a) -> a.length == 0 ? null : new MessageFormat("Total <b>{0}</b>", Locale.ROOT).format(a),
        new DefaultLinks(""),
        Forms.NONE);
  }

  private static TemplateEntry compile(String body) {
    return Compilation.builder()
        .template("page.html", "<div t:params=\"java.math.BigDecimal x\">" + body + "</div>")
        .messages(BUNDLE)
        .compile()
        .assertSuccess()
        .registry()
        .find("page")
        .orElseThrow();
  }

  /** An application subclass whose own precision and scale say 1 but whose toString() is its label. */
  private static final class Labeled extends BigDecimal {
    private static final long serialVersionUID = 1L;
    private final String label;

    Labeled(String label) {
      super(1);
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private static String render(TemplateEntry page, BigDecimal x) {
    StringOutput out = new StringOutput();
    page.renderer().render(Map.of("x", x), ctx(), out);
    return out.toString();
  }

  @ParameterizedTest
  @FieldSource("FORMS")
  void hugeExponentFailsTheRenderFast(String body) {
    TemplateEntry page = compile(body);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> assertThrows(IllegalArgumentException.class, () -> render(page, new BigDecimal("1E+999999999"))));
    assertThrows(IllegalArgumentException.class, () -> render(page, new BigDecimal("1E+10000")));
    // MessageFormat copies a subclass's toString(), so its own precision and scale must not pass it.
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> assertThrows(IllegalArgumentException.class, () -> render(page, new Labeled("1E+999999999"))));
  }

  @ParameterizedTest
  @FieldSource("FORMS")
  void normalValuesRenderUnchanged(String body) {
    TemplateEntry page = compile(body);
    for (String value : new String[] {"1234.5", "1E+3", "-7", "0.001"}) {
      String formatted = new MessageFormat("{0}", Locale.ROOT).format(new Object[] {new BigDecimal(value)});
      String html = render(page, new BigDecimal(value));
      assertTrue(html.contains(formatted), body + " with " + value + ": " + html);
    }
    // 10,000 integer digits is the largest value that renders: 13,333 characters with separators.
    String html = render(page, new BigDecimal("1E+9999"));
    String formatted = new MessageFormat("{0}", Locale.ROOT).format(new Object[] {new BigDecimal("1E+9999")});
    assertEquals(13333, formatted.length());
    assertTrue(html.contains(formatted), body);
  }
}
