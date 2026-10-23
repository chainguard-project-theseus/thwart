package dev.tessera.compiler.processor;

import static dev.tessera.compiler.testing.Compilation.model;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.testing.Compilation;
import java.util.ServiceLoader;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #233: getters Lombok adds during annotation processing are template properties whatever the
 * processor order. Lombok may run after Tessera in a round; Tessera retries failed templates in
 * later rounds and reports errors only in the last one.
 */
class LombokTest {
  private static final String PAGE =
      """
      package demo;
      import dev.tessera.runtime.TemplateView;
      import lombok.Getter;
      @TemplateView
      @Getter
      public class PageInfo {
        private final String locale;
        private final boolean active;
        public PageInfo(String locale, boolean active) { this.locale = locale; this.active = active; }
      }
      """;
  private static final String OWNER =
      """
      package demo;
      import dev.tessera.runtime.TemplateView;
      import lombok.AllArgsConstructor;
      import lombok.Data;
      @TemplateView
      @Data
      @AllArgsConstructor
      public class Owner {
        private String name;
        private int visits;
      }
      """;
  private static final String SHOUT =
      """
      package demo;
      import dev.tessera.runtime.TemplateFunction;
      import dev.tessera.runtime.TemplateFunctions;
      @TemplateFunctions("shout")
      public final class Shout {
        private Shout() {}
        @TemplateFunction public static String loud(String s) { return s.toUpperCase() + "!"; }
      }
      """;

  private static Compilation.Builder withLombok(boolean lombokFirst) {
    return Compilation.builder().processor(lombok(), lombokFirst);
  }

  /** Lombok's javac processor, found the way javac finds it (its class is package-private). */
  private static Processor lombok() {
    for (Processor p : ServiceLoader.load(Processor.class)) {
      if (p.getClass().getName().startsWith("lombok.")) {
        return p;
      }
    }
    throw new AssertionError("Lombok is not on the test classpath");
  }

  @ParameterizedTest(name = "lombokFirst={0}")
  @ValueSource(booleans = {true, false})
  void getterAndDataPropertiesCompileInEitherOrder(boolean lombokFirst) {
    Compilation c =
        withLombok(lombokFirst)
            .java("demo.PageInfo", PAGE)
            .java("demo.Owner", OWNER)
            .java("demo.Shout", SHOUT)
            .template(
                "page.html",
                "<p xmlns:t=\"https://tessera.dev\" t:params=\"demo.PageInfo page, demo.Owner owner\""
                    + " t:if=\"${page.active}\" t:text=\"|${page.locale} ${shout.loud(owner.name)} ${owner.visits}|\">x</p>")
            .compile()
            .assertSuccess();
    assertEquals(
        "<p>en ANN &lt;B&gt;! 3</p>",
        c.render(
            "page",
            model("page", c.newInstance("demo.PageInfo", "en", true), "owner", c.newInstance("demo.Owner", "Ann <b>", 3))));
  }

  @ParameterizedTest(name = "lombokFirst={0}")
  @ValueSource(booleans = {true, false})
  void aMissingPropertyIsStillOneErrorWithItsPosition(boolean lombokFirst) {
    Compilation c =
        withLombok(lombokFirst)
            .java("demo.PageInfo", PAGE)
            .template(
                "page.html",
                "<p xmlns:t=\"https://tessera.dev\" t:params=\"demo.PageInfo page\" t:text=\"${page.missing}\">x</p>")
            .compile()
            .assertError("page.html:1:74", "'missing' is not a property of demo.PageInfo");
    assertEquals(1, c.errors().size(), String.join("\n", c.errors()));
  }

  @Test
  void aPrivateFieldWithoutLombokIsNotAProperty() {
    Compilation.builder()
        .java(
            "demo.Plain",
            """
            package demo;
            @dev.tessera.runtime.TemplateView
            public class Plain { private String locale; }
            """)
        .template("plain.html", "<p xmlns:t=\"https://tessera.dev\" t:params=\"demo.Plain p\" t:text=\"${p.locale}\">x</p>")
        .compile()
        .assertError("plain.html:1:", "'locale' is not a property of demo.Plain");
  }
}
