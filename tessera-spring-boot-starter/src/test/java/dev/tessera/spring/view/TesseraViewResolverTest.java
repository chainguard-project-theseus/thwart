package dev.tessera.spring.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.runtime.RenderContext;
import java.util.Locale;
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
}
