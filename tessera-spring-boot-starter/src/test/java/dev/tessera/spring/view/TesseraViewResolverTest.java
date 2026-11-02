package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.TemplateEntry;
import dev.tessera.runtime.TemplateRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;

class TesseraViewResolverTest {

  private final TesseraViewResolver resolver =
      new TesseraViewResolver(registry(), (request, response, model) -> RenderContext.defaults());

  /** The registry names of {@code ViewNameInjectionTest}, whose payloads run here too. */
  static TemplateRegistry registry() {
    TemplateEntry.ModelRenderer page = (model, ctx, out) -> out.write("<p>page</p>");
    List<TemplateEntry.Param> none = Collections.emptyList();
    return TemplateRegistry.of(
        Arrays.asList(
            new TemplateEntry("greeting", none, page),
            new TemplateEntry("owners/details", none, page),
            new TemplateEntry("user/en/welcome", none, page)),
        // A fragment entry, as the compiler generates for a "page :: name" view (#131).
        Collections.singletonList(new TemplateEntry("greeting :: row", none, page)));
  }

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

  @Test
  void oneViewPerRegistryEntry() {
    // Every name that finds the same entry gets the same view; each entry its own.
    assertThat(resolver.resolveViewName("/greeting", Locale.ROOT))
        .isSameAs(resolver.resolveViewName("greeting", Locale.ROOT));
    assertThat(resolver.resolveViewName("greeting::row", Locale.ROOT))
        .isSameAs(resolver.resolveViewName("greeting :: row", Locale.ROOT))
        .isNotSameAs(resolver.resolveViewName("greeting", Locale.ROOT));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "greeting :: row",
        "greeting::row",
        "greeting   ::  row",
        "greeting\t::\nrow",
        " greeting :: row ",
        "/greeting :: row",
        " /greeting::row"
      })
  void fragmentNamesResolve(String name) {
    // Thymeleaf's "page :: fragment" view names (#131): whitespace around "::" as it trims it.
    assertThat(TesseraViewResolver.lookupName(name)).isEqualTo("greeting :: row");
    assertThat(resolver.resolveViewName(name, Locale.ROOT)).isInstanceOf(TesseraView.class);
  }

  @ParameterizedTest
  @FieldSource("dev.tessera.spring.security.ViewNameInjectionTest#PAYLOADS")
  void payloadsFallThrough(String name) {
    assertThat(resolver.resolveViewName(name, Locale.ROOT)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/"})
  void emptyNamesFallThrough(String name) {
    // Resolver-only: names that are empty, or empty after the dropped leading "/".
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
    TemplateRegistry registry = registry();
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
