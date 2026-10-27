package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A {@link Date} in the model (Boot's error {@code timestamp}) reaches a param declared {@code
 * Instant} or {@code ZonedDateTime} converted; anything else is passed through unchanged.
 */
class TesseraViewTest {

  static final long MILLIS = 1761188903000L;

  @Test
  void dateFillsInstantAndZonedDateTimeParams() throws Exception {
    Map<String, Object> model = new HashMap<String, Object>();
    model.put("instant", new Date(MILLIS));
    model.put("zoned", new Timestamp(MILLIS));
    model.put("text", "unchanged");
    Map<String, ?> seen = render(model);
    assertThat(seen.get("instant")).isEqualTo(Instant.ofEpochMilli(MILLIS));
    assertThat(seen.get("zoned"))
        .isEqualTo(Instant.ofEpochMilli(MILLIS).atZone(ZoneId.systemDefault()));
    assertThat(seen.get("text")).isEqualTo("unchanged");
    assertThat(model.get("instant")).isInstanceOf(Date.class); // the caller's model is not changed
  }

  @Test
  void dateForAnyOtherParamIsNotConverted() throws Exception {
    Date date = new Date(MILLIS);
    Map<String, Object> model = new HashMap<String, Object>();
    model.put("text", date);
    model.put("instant", Instant.EPOCH);
    Map<String, ?> seen = render(model);
    assertThat(seen.get("text")).isSameAs(date); // the adapter rejects it as not a String
    assertThat(seen.get("instant")).isEqualTo(Instant.EPOCH);
  }

  private static Map<String, ?> render(Map<String, Object> model) throws Exception {
    final Map<String, Object> seen = new HashMap<String, Object>();
    TemplateEntry entry =
        new TemplateEntry(
            "dates",
            Arrays.asList(
                new TemplateEntry.Param("instant", "java.time.Instant"),
                new TemplateEntry.Param("zoned", "java.time.ZonedDateTime"),
                new TemplateEntry.Param("text", "java.lang.String")),
            (m, ctx, out) -> seen.putAll(m));
    TesseraView.of(entry, (request, response, m) -> RenderContext.defaults())
        .render(model, new MockHttpServletRequest(), new MockHttpServletResponse());
    return seen;
  }
}
