package dev.tessera.compiler.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.processor.ProcessorOptions;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.compiler.testing.ExprRunner;
import dev.tessera.compiler.testing.ExprRunner.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCompilerTest {
  private static final String OWNER =
      "package com.example; public record Owner(String firstName, int pets, Object secret) {}";
  private static final String CTX =
      "new dev.tessera.runtime.RenderContext(java.util.Locale.ROOT,"
          + " (k, a) -> k + java.util.Arrays.toString(a), new dev.tessera.runtime.DefaultLinks(\"\"),"
          + " dev.tessera.runtime.Forms.NONE)";

  private static ExprRunner owners() {
    return ExprRunner.create()
        .java("com.example.Owner", OWNER)
        .var("o", "com.example.Owner", "new com.example.Owner(\"Ann\", 3, null)");
  }

  @Test
  void keyWithoutArgsRendersThroughCtxMessages() {
    ExprRunner r = owners();
    assertEquals("ctx.messages().get(\"welcome\", dev.tessera.runtime.Messages.NO_ARGS)", r.compile("#{welcome}").code());
    assertEquals("welcome[]", r.eval("#{welcome}", CTX));
  }

  @Test
  void keyWithArgsPassesTypedValues() {
    assertEquals("owner.greeting[Ann, 3, x]", owners().eval("#{owner.greeting(o.firstName, o.pets, 'x')}", CTX));
  }

  @Test
  void loneNullArgIsOneArgumentNotTheVarargsArray() {
    assertEquals("k[null]", owners().eval("#{k(null)}", CTX));
  }

  @Test
  void rendersThroughResourceBundleMessagesWithMessageFormat() {
    String rb =
        "new dev.tessera.runtime.RenderContext(java.util.Locale.ROOT,"
            + " (k, a) -> new java.text.MessageFormat(\"Hello {0}, {1} pets\", java.util.Locale.ROOT).format(a),"
            + " new dev.tessera.runtime.DefaultLinks(\"\"), dev.tessera.runtime.Forms.NONE)";
    assertEquals("Hello Ann, 3 pets", owners().eval("#{hello(o.firstName, o.pets)}", rb));
  }

  @Test
  void checkedBundleAcceptsKnownKeys() {
    Result res = owners().bundle("welcome=Hi\nowner.greeting=Hello {0}\n").compile("#{owner.greeting(o.firstName)}");
    assertEquals(List.of(), res.diagnostics());
  }

  @Test
  void checkedBundleRejectsUnknownKeyNamingKeyAndBundle() {
    Result res = owners().bundle("welcome=Hi\n").compile("#{welcom}");
    assertNull(res.code());
    assertEquals(1, res.errors().size(), res.diagnostics().toString());
    String e = res.errors().get(0);
    assertTrue(e.startsWith("t.html:1:1: error: "), e);
    assertTrue(e.contains("'welcom'"), e);
    assertTrue(e.contains("messages.properties"), e);
  }

  @Test
  void uncheckedBundleWarnsOncePerTemplate() {
    List<Result> res = owners().compileAll("#{a}", "#{b(o.firstName)}");
    assertTrue(res.get(0).code() != null && res.get(1).code() != null);
    assertEquals(1, res.get(0).warnings().size(), res.get(0).diagnostics().toString());
    assertTrue(res.get(0).warnings().get(0).contains("-Atessera.messages"), res.get(0).warnings().toString());
    assertEquals(List.of(), res.get(1).diagnostics());
  }

  @Test
  void argumentMustBeViewSafe() {
    Result res = owners().compile("#{k(o.secret)}");
    assertNull(res.code());
    assertTrue(res.errors().stream().anyMatch(e -> e.contains("java.lang.Object")), res.diagnostics().toString());
  }

  @Test
  void argumentErrorsAreReported() {
    Result res = owners().compile("#{k(o.nope)}");
    assertNull(res.code());
    assertTrue(res.errors().stream().anyMatch(e -> e.contains("nope")), res.diagnostics().toString());
  }

  @Test
  void bundleFileLoadsKeys() {
    MessageBundle m = load("a=1\nb.c = 2\n# comment\nd\\:e=3\n");
    assertTrue(m.isChecked());
    assertTrue(m.contains("a") && m.contains("b.c") && m.contains("d:e"));
    assertTrue(!m.contains("comment") && !m.contains("x"));
    assertTrue(m.source().endsWith("messages.properties"), m.source());
    MessageBundle u = MessageBundle.unchecked();
    assertTrue(!u.isChecked() && u.contains("anything"));
  }

  @Test
  void processorReportsMissingBundle() {
    Compilation.builder()
        .template("a.html", "<p>x</p>")
        .option(ProcessorOptions.MESSAGES, "/nonexistent/tessera/messages")
        .compile()
        .assertError("message bundle does not exist", "/nonexistent/tessera/messages.properties");
  }

  @Test
  void processorLoadsConfiguredBundle() {
    Compilation.builder().template("a.html", "<p>x</p>").messages("a=1\n").compile().assertSuccess();
  }

  private static MessageBundle load(String properties) {
    return ExprRunner.loadBundle(properties);
  }
}
