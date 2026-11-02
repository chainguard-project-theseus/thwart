package dev.tessera.compiler.security;

import dev.tessera.compiler.testing.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Negative (#661): the {@code HtmlPolicy} widenings that let stored HTML fetch remote images, show
 * inline {@code data:} images or write ids outside {@code user-content-} are gone from the API, so
 * application code that calls them doesn't compile.
 */
class HtmlPolicyWideningsTest {

  private static Compilation compile(String call) {
    return Compilation.builder()
        .java("com.example.Policies",
            "package com.example;\n"
                + "import dev.tessera.runtime.HtmlPolicy;\n"
                + "final class Policies {\n"
                + "  static final HtmlPolicy POLICY = HtmlPolicy.builder().allowElements(\"img\", \"a\")" + call + ".build();\n"
                + "}\n")
        .compile();
  }

  @ParameterizedTest
  @ValueSource(strings = {".allowRemoteImages()", ".allowDataImages()", ".idPrefix(\"main-\")"})
  void removedWideningsDoNotCompile(String call) {
    compile(call).assertError("cannot find symbol", call.substring(1, call.indexOf('(')));
  }

  /** Positive control: the same source with a kept builder method compiles. */
  @Test
  void keptBuilderMethodsCompile() {
    compile(".allowLinkTarget().linkRel().allowClasses(\"status-*\")").assertSuccess();
  }
}
