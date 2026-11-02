package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.internal.TemplateEntry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.View;

/**
 * A {@link Date} in the model (Boot's error {@code timestamp}) reaches a param declared {@code
 * Instant} or {@code ZonedDateTime} converted; anything else is passed through unchanged. The
 * handler's path variables are merged under the model.
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

  @Test
  void pathVariablesAreMergedUnderTheModel() throws Exception {
    Map<String, Object> vars = new HashMap<String, Object>();
    vars.put("text", "from path");
    vars.put("ownerId", 7L);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(View.PATH_VARIABLES, vars);
    Map<String, Object> model = new HashMap<String, Object>();
    model.put("instant", Instant.EPOCH);
    Map<String, ?> seen = render(model, request);
    assertThat(seen.get("text")).isEqualTo("from path");
    assertThat(seen.get("ownerId")).isEqualTo(7L);
    assertThat(model).containsOnlyKeys("instant"); // the caller's model is not changed

    model.put("text", "from model");
    assertThat(render(model, request).get("text")).isEqualTo("from model"); // the model wins
  }

  @Test
  void pathVariablesAreMergedUnderANullModel() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(View.PATH_VARIABLES, Collections.singletonMap("text", "x"));
    assertThat(render(null, request).get("text")).isEqualTo("x");
  }

  /** The page is written as UTF-8 bytes with its length (#685). */
  @Test
  void writesUtf8BytesWithContentLength() throws Exception {
    TemplateEntry entry =
        new TemplateEntry(
            "page", Collections.<TemplateEntry.Param>emptyList(), (m, ctx, out) -> out.write("caf" + (char) 0xE9));
    MockHttpServletResponse response = new MockHttpServletResponse();
    TesseraView.of(entry, (request, r, m) -> RenderContext.defaults())
        .render(null, new MockHttpServletRequest(), response);
    assertThat(response.getContentLength()).isEqualTo(5);
    assertThat(response.getContentType()).isEqualTo(TesseraView.CONTENT_TYPE);
    assertThat(response.getContentAsString()).isEqualTo("caf" + (char) 0xE9);
  }

  private static Map<String, ?> render(Map<String, Object> model) throws Exception {
    return render(model, new MockHttpServletRequest());
  }

  private static Map<String, ?> render(Map<String, Object> model, MockHttpServletRequest req)
      throws Exception {
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
        .render(model, req, new MockHttpServletResponse());
    return seen;
  }
}
