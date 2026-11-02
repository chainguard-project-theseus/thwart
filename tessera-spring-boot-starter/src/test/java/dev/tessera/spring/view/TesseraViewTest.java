package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
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

  /** The default policy names the nonce the page was stamped with (#757). */
  @Test
  void defaultPolicyCarriesTheContextNonce() throws Exception {
    MockHttpServletResponse response = renderWithNonce("Ab+/9_-x==", true, null);
    assertThat(response.getHeader(TesseraView.CSP_HEADER))
        .isEqualTo(
            "script-src 'nonce-Ab+/9_-x==' 'strict-dynamic'; object-src 'none'; base-uri 'none'");
  }

  @Test
  void existingPolicyIsKept() throws Exception {
    MockHttpServletResponse response = renderWithNonce("abc", true, "default-src 'self'");
    assertThat(response.getHeaders(TesseraView.CSP_HEADER)).containsExactly("default-src 'self'");
  }

  @Test
  void disabledOrNoNonceSendsNoPolicy() throws Exception {
    assertThat(renderWithNonce("abc", false, null).containsHeader(TesseraView.CSP_HEADER)).isFalse();
    assertThat(renderWithNonce(null, true, null).containsHeader(TesseraView.CSP_HEADER)).isFalse();
  }

  /** A nonce that is not a CSP base64-value would rewrite the policy; it fails the render. */
  @Test
  void nonceThatIsNotBase64IsAnError() {
    for (String bad : Arrays.asList("a' 'unsafe-inline", "a; script-src *", "a b", "a\r\nX: y")) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      assertThatThrownBy(() -> view(bad, true).render(null, new MockHttpServletRequest(), response))
          .isInstanceOf(IllegalStateException.class);
      assertThat(response.containsHeader(TesseraView.CSP_HEADER)).isFalse();
      assertThat(response.getContentLength()).isEqualTo(0); // nothing was written
    }
  }

  private static TesseraView view(String nonce, boolean csp) {
    TemplateEntry entry =
        new TemplateEntry(
            "page", Collections.<TemplateEntry.Param>emptyList(), (m, ctx, out) -> out.write("x"));
    return TesseraView.of(
        entry, (request, r, m) -> RenderContext.defaults().withCspNonce(nonce), csp);
  }

  private static MockHttpServletResponse renderWithNonce(
      String nonce, boolean csp, String existing) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    if (existing != null) {
      response.setHeader(TesseraView.CSP_HEADER, existing);
    }
    view(nonce, csp).render(null, new MockHttpServletRequest(), response);
    assertThat(response.getContentAsString()).isEqualTo("x");
    return response;
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
