package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;

class TesseraViewResolverTest {

  private final TesseraViewResolver resolver =
      new TesseraViewResolver(
          TesseraMvcTest.registry(), (request, response, model) -> RenderContext.defaults());

  @Test
  void exactNamesResolve() {
    assertThat(resolver.resolveViewName("greeting", Locale.ROOT)).isInstanceOf(TesseraView.class);
    assertThat(resolver.resolveViewName("owners/details", Locale.ROOT))
        .isInstanceOf(TesseraView.class);
    assertThat(resolver.resolveViewName("greeting", Locale.ROOT).getContentType())
        .isEqualTo("text/html;charset=UTF-8");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "nope",
        "../greeting",
        "../x",
        "x :: frag",
        "greeting :: frag",
        "__${T(java.lang.Runtime)}__",
        "__${T(java.lang.Runtime)}__::.x",
        "x?y",
        "greeting?y",
        "greeting#x",
        "greeting;x",
        "greeting.html",
        "owners/../greeting",
        "owners/./details",
        "/owners/details",
        "redirect:greeting",
        "forward:greeting",
        "greeting\u0000"
      })
  void anythingElseFallsThrough(String name) {
    assertThat(resolver.resolveViewName(name, Locale.ROOT)).isNull();
  }

  @Test
  void nullNameFallsThrough() {
    assertThat(resolver.resolveViewName(null, Locale.ROOT)).isNull();
  }

  @Test
  void orderedBeforeBeanNameAndThymeleafResolvers() {
    // Before Thymeleaf's resolver (LOWEST_PRECEDENCE - 5) and Boot's BeanNameViewResolver
    // (LOWEST_PRECEDENCE - 10).
    assertThat(resolver.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE - 10);
  }

  @Test
  void warnsWhenNoTemplatesAreOnTheClasspath() {
    RecordingLog log = new RecordingLog();
    TesseraViewResolver.logTemplateCount(TemplateRegistry.of(Collections.emptyList()), log);
    assertThat(log.warn).containsExactly(TesseraViewResolver.NO_TEMPLATES);
    assertThat(log.info).isEmpty();
  }

  @Test
  void logsTheTemplateCount() {
    TemplateRegistry registry = TesseraMvcTest.registry();
    RecordingLog log = new RecordingLog();
    TesseraViewResolver.logTemplateCount(registry, log);
    assertThat(log.info)
        .containsExactly("Found " + registry.all().size() + " compiled Tessera templates");
    assertThat(log.warn).isEmpty();
  }

  @SuppressWarnings("serial") // NoOpLog is Serializable; this test double is never serialized
  private static final class RecordingLog extends NoOpLog {
    final List<Object> info = new ArrayList<>();
    final List<Object> warn = new ArrayList<>();

    @Override
    public void info(Object message) {
      info.add(message);
    }

    @Override
    public void warn(Object message) {
      warn.add(message);
    }
  }
}
