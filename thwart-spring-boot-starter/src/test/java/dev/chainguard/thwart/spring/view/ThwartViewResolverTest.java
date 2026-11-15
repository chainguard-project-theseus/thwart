package dev.chainguard.thwart.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chainguard.thwart.runtime.RenderContext;
import dev.chainguard.thwart.runtime.internal.TemplateEntry;
import dev.chainguard.thwart.runtime.TemplateRegistry;
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

class ThwartViewResolverTest {

  /**
   * View names that Thymeleaf evaluated as expressions (Spring View Manipulation, CVE-2021-43466,
   * CVE-2026-40478), plus fragment selectors. They run here against the resolver, and end to end
   * in {@code ViewNameInjectionTest}; they live here so the Spring modules without Boot run them.
   */
  static final List<String> PAYLOADS =
      Arrays.asList(
          "__${T(java.lang.Runtime).getRuntime().exec('id')}__::.x",
          "__${new java.util.Scanner(T(java.lang.Runtime).getRuntime().exec('id').getInputStream()).next()}__::.x",
          "__${T (java.lang.Runtime).getRuntime().exec('id')}__::.x",
          "__${new\tjava.io.File('/etc/passwd')}__::x",
          "__|${T(java.lang.Runtime)}|__::x",
          "__${T(java.lang.Runtime)}__",
          "__${#ctx}__",
          "__${@environment.getProperty('x')}__",
          "${T(java.lang.Runtime).getRuntime().exec('id')}",
          "*{T(java.lang.Runtime)}",
          "#{T(java.lang.Runtime)}",
          "@{T(java.lang.Runtime)}",
          "~{greeting :: frag}",
          "~{greeting}",
          "~{::frag}",
          "greeting :: frag",
          "greeting::frag",
          "greeting :: #main",
          "greeting :: div.x",
          "greeting :: ${T(java.lang.Runtime)}",
          "greeting :: __${T(java.lang.Runtime)}__",
          "greeting::__${7*7}__",
          // Fragment syntax Thwart doesn't support, and names near the registered "greeting :: row".
          "greeting :: row(x=1)",
          "greeting::row()",
          "greeting :: row (x)",
          "greeting :: #row",
          "greeting :: .row",
          "greeting :: tr",
          "greeting :: [id='row']",
          "greeting :: ${row}",
          "greeting :: __${row}__",
          "~{greeting :: row}",
          "greeting :: ~{row}",
          "greeting :: row :: row",
          "greeting :: greeting :: row",
          "greeting :: ROW",
          "greeting :: ../row",
          "greeting :: row/",
          "greeting :: ",
          ":: row",
          "this :: row",
          "../greeting :: row",
          "//greeting :: row",
          "owners/../greeting :: row",
          "greeting.html :: row",
          "greeting : : row",
          "owners/details :: row",
          "|greeting|",
          "greeting ",
          " greeting",
          "greeting/",
          "GREETING",
          "greeting\n",
          "Greeting",
          "greeting.html",
          "./greeting",
          "//greeting",
          "/./greeting",
          "/../greeting",
          "/greeting :: frag",
          "../greeting",
          "owners/../greeting",
          "owners/./details",
          "owners//details",
          "owners\\details",
          "owners%2Fdetails",
          "greeting;x",
          "greeting?x",
          "greeting#x",
          "greeting\u0000",
          "forward:greeting",
          "redirect:greeting",
          // More near misses of the registered page and fragment names.
          "nope",
          "x?y",
          "../x",
          "x :: frag",
          "__${T(java.lang.Runtime)}__::.x",
          "~{greeting :: row",
          "greeting ::",
          "greeting :: r ow",
          "greeting :: row\u0000x",
          "greeting :: row.html",
          "greeting :: owners/details",
          "Greeting :: row",
          "./greeting :: row",
          "greeting/ :: row",
          "owners :: details :: row",
          "redirect:greeting :: row",
          "//owners/details",
          "/ greeting",
          "/greeting/",
          "\\greeting");

  private final ThwartViewResolver resolver =
      new ThwartViewResolver(registry(), (request, response, model) -> RenderContext.defaults());

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
    assertThat(resolver.resolveViewName("greeting", Locale.ROOT)).isInstanceOf(ThwartView.class);
    assertThat(resolver.resolveViewName("owners/details", Locale.ROOT))
        .isInstanceOf(ThwartView.class);
    assertThat(resolver.resolveViewName("greeting", Locale.ROOT).getContentType())
        .isEqualTo("text/html;charset=UTF-8");
  }

  @Test
  void oneLeadingSlashIsDropped() {
    // As with Thymeleaf's and FreeMarker's prefix + name (#131).
    assertThat(resolver.resolveViewName("/greeting", Locale.ROOT)).isInstanceOf(ThwartView.class);
    assertThat(resolver.resolveViewName("/owners/details", Locale.ROOT))
        .isInstanceOf(ThwartView.class);
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
    assertThat(ThwartViewResolver.lookupName(name)).isEqualTo("greeting :: row");
    assertThat(resolver.resolveViewName(name, Locale.ROOT)).isInstanceOf(ThwartView.class);
  }

  @ParameterizedTest
  @FieldSource("PAYLOADS")
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
    ThwartViewResolver.logTemplateCount(TemplateRegistry.of(Collections.emptyList()), log);
    assertThat(log.warn).containsExactly(ThwartViewResolver.NO_TEMPLATES);
    assertThat(log.info).isEmpty();
  }

  @Test
  void logsTheTemplateCount() {
    TemplateRegistry registry = registry();
    RecordingLog log = new RecordingLog();
    ThwartViewResolver.logTemplateCount(registry, log);
    assertThat(log.info)
        .containsExactly("Found " + registry.all().size() + " compiled Thwart templates");
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
