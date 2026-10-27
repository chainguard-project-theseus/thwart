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

  @Test
  void oneLeadingSlashIsDropped() {
    // As with Thymeleaf's and FreeMarker's prefix + name (#131).
    assertThat(resolver.resolveViewName("/greeting", Locale.ROOT)).isInstanceOf(TesseraView.class);
    assertThat(resolver.resolveViewName("/owners/details", Locale.ROOT))
        .isInstanceOf(TesseraView.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "owners/details :: row",
        "owners/details::row",
        "owners/details   ::  row",
        "owners/details\t::\nrow",
        " owners/details :: row ",
        "/owners/details :: row",
        " /owners/details::row"
      })
  void fragmentNamesResolve(String name) {
    // Thymeleaf's "page :: fragment" view names (#131): whitespace around "::" as it trims it.
    assertThat(TesseraViewResolver.lookupName(name)).isEqualTo("owners/details :: row");
    assertThat(resolver.resolveViewName(name, Locale.ROOT)).isInstanceOf(TesseraView.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Thymeleaf fragment syntax Tessera doesn't support (#131): it is never parsed.
        "owners/details :: row(x=1)",
        "owners/details :: row()",
        "owners/details :: row (x)",
        "owners/details :: #row",
        "owners/details :: .row",
        "owners/details :: tr",
        "owners/details :: [id='row']",
        "owners/details :: ${frag}",
        "owners/details :: __${frag}__",
        "~{owners/details :: row}",
        "~{owners/details :: row",
        "owners/details :: ~{row}",
        "owners/details :: row :: row",
        "owners/details :: details :: row",
        "owners/details :: ROW",
        "owners/details :: ",
        "owners/details ::",
        ":: row",
        "this :: row",
        "owners/details : : row",
        "owners/details :: row/",
        "owners/details :: r ow",
        "owners/details :: row\u0000x",
        // Traversal and other names a page name doesn't resolve either.
        "owners/../owners/details :: row",
        "owners/./details :: row",
        "../owners/details :: row",
        "//owners/details :: row",
        "owners/details.html :: row",
        "owners/details/ :: row",
        "owners :: details :: row",
        "Owners/details :: row",
        "owners/details :: ../row",
        "owners/details :: row.html",
        "greeting :: row",
        "redirect:owners/details :: row",
        // A fragment entry's name is not a template name.
        "owners/details :: greeting"
      })
  void unsupportedFragmentNamesFallThrough(String name) {
    assertThat(resolver.resolveViewName(name, Locale.ROOT)).isNull();
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
        "/",
        "//greeting",
        "//owners/details",
        "/./greeting",
        "/../greeting",
        "/ greeting",
        "/greeting/",
        "/greeting :: frag",
        "\\greeting",
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
