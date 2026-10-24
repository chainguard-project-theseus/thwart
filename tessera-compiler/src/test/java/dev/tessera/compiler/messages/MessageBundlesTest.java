package dev.tessera.compiler.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.processor.ProcessorOptions;
import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code -Atessera.messages} with several basenames and locale-suffixed basenames (#134). */
class MessageBundlesTest {
  @TempDir Path dir;

  private void write(String name, String properties) throws IOException {
    Path f = dir.resolve(name);
    Files.createDirectories(f.getParent());
    Files.writeString(f, properties, StandardCharsets.UTF_8);
  }

  private Compilation compile(String option, String template) {
    return Compilation.builder()
        .template("page.html", template)
        .option(ProcessorOptions.MESSAGES, option)
        .compile();
  }

  /** espd: a partial base file, full English in messages_en, other locales beside them. */
  private void espd() throws IOException {
    write("i18n/messages.properties", "title=ESPD\n");
    write("i18n/messages_en.properties", "title=ESPD\nintro=<p>Please <b>save</b></p>\n");
    write("i18n/messages_de.properties", "intro=<p>Bitte <b>speichern</b></p>\nonly.de=x\n");
  }

  @Test
  void localeSuffixedBaseLoadsTheOtherLocales() throws IOException {
    espd();
    Compilation c =
        compile(dir.resolve("i18n/messages_en").toString(), "<div><p t:utext=\"#{intro}\">x</p><h1 t:text=\"#{title}\">x</h1></div>")
            .assertSuccess();
    // The German value was checked at build time, so it is printed as HTML, not escaped.
    RenderContext de =
        new RenderContext(Locale.GERMAN, (k, a) -> "<p>Bitte <b>speichern</b></p>", new DefaultLinks(""), Forms.NONE);
    assertEquals(
        "<div><p><p>Bitte <b>speichern</b></p></p><h1>&lt;p&gt;Bitte &lt;b&gt;speichern&lt;/b&gt;&lt;/p&gt;</h1></div>",
        c.render("page", Map.of(), de));
  }

  @Test
  void badValueInAnotherLocaleOfALocaleSuffixedBaseFails() throws IOException {
    espd();
    write("i18n/messages_fr.properties", "intro=<p onclick=\"x()\">Salut</p>\n");
    compile(dir.resolve("i18n/messages_en").toString(), "<div>\n<p t:utext=\"#{intro}\">x</p></div>")
        .assertError("page.html:2:", "messages_fr.properties", "attribute onclick is not allowed");
  }

  @Test
  void keyMissingFromALocaleFileFallsBackToTheDefault() throws IOException {
    espd(); // messages_de has no 'title'
    compile(dir.resolve("i18n/messages_en").toString(), "<p t:text=\"#{title}\">x</p>").assertSuccess();
  }

  @Test
  void keyOnlyInANonDefaultLocaleIsUnknown() throws IOException {
    espd();
    compile(dir.resolve("i18n/messages_en").toString(), "<p t:text=\"#{only.de}\">x</p>")
        .assertError("unknown message key 'only.de'", "messages_en.properties", "it is only in", "messages_de.properties");
  }

  @Test
  void plainBaseHintsAtTheDefaultLocale() throws IOException {
    espd();
    compile(dir.resolve("i18n/messages").toString(), "<p t:utext=\"#{intro}\">x</p>")
        .assertError("unknown message key 'intro'", "it is only in", "messages_en.properties", "<base>_en");
  }

  /** fredbet: several basenames, one with only _de/_en files. */
  @Test
  void severalBasenames() throws IOException {
    write("msgs/TranslationMessages_de.properties", "bet.title=Wetten\n");
    write("msgs/TranslationMessages_en.properties", "bet.title=Bets\nen.only=x\n");
    write("ValidationMessages.properties", "NotNull=must not be null\n");
    String option = dir.resolve("msgs/TranslationMessages_de") + " , " + dir.resolve("ValidationMessages");
    compile(option, "<div><p t:text=\"#{bet.title}\">x</p><p t:text=\"#{NotNull}\">x</p></div>").assertSuccess();
    compile(option, "<p t:text=\"#{nope}\">x</p>")
        .assertError("unknown message key 'nope'", "TranslationMessages_de.properties", "ValidationMessages.properties");
    compile(option, "<p t:text=\"#{en.only}\">x</p>").assertError("unknown message key 'en.only'");
  }

  @Test
  void baseWithOnlyLocaleFilesNeedsADefaultLocale() throws IOException {
    write("msgs/TranslationMessages_de.properties", "a=b\n");
    write("msgs/TranslationMessages_en.properties", "a=b\n");
    compile(dir.resolve("msgs/TranslationMessages").toString(), "<p>x</p>")
        .assertError(
            "Tessera: message bundle does not exist: ",
            "TranslationMessages.properties; it has locale files TranslationMessages_de.properties,"
                + " TranslationMessages_en.properties",
            "TranslationMessages_de");
  }

  @Test
  void missingDefaultLocaleFileFails() throws IOException {
    espd();
    compile(dir.resolve("i18n/messages_it").toString(), "<p>x</p>")
        .assertError("Tessera: message bundle does not exist: ", "messages_it.properties");
  }

  @Test
  void missingSecondBasenameFails() throws IOException {
    espd();
    compile(dir.resolve("i18n/messages") + "," + dir.resolve("nope"), "<p>x</p>")
        .assertError("Tessera: message bundle does not exist: ", "nope.properties");
  }

  @Test
  void suffixThatIsNotALanguageIsPartOfTheBasename() throws IOException {
    write("labels_msg.properties", "a=b\n");
    compile(dir.resolve("labels_msg").toString(), "<p t:text=\"#{a}\">x</p>").assertSuccess();
  }

  @Test
  void warnsAboutFilesBesideTheBaseThatAreNotLoaded() throws IOException {
    espd();
    write("i18n/messages_en-US.properties", "intro=<script>alert(1)</script>\n");
    write("i18n/other.properties", "x=y\n");
    Compilation c =
        compile(dir.resolve("i18n/messages_en").toString(), "<p t:utext=\"#{intro}\">x</p>").assertSuccess();
    List<String> warnings =
        c.diagnostics().stream().filter(d -> d.startsWith("WARNING: Tessera: message file")).toList();
    assertEquals(1, warnings.size(), c.diagnostics().toString());
    assertTrue(warnings.get(0).contains("messages_en-US.properties is not loaded"), warnings.get(0));
  }

  @Test
  void noWarningWhenEveryFileIsALocale() throws IOException {
    espd();
    Compilation c = compile(dir.resolve("i18n/messages_en").toString(), "<p>x</p>").assertSuccess();
    assertTrue(c.diagnostics().stream().noneMatch(d -> d.contains("message file")), c.diagnostics().toString());
  }

  @Test
  void optionIsACommaSeparatedList() {
    assertEquals(
        List.of(Paths.get("/a/messages"), Paths.get("/b/Validation_en")),
        ProcessorOptions.parse(Map.of(ProcessorOptions.MESSAGES, " /a/messages, ,/b/Validation_en ")).messages());
    assertEquals(List.of(), ProcessorOptions.parse(Map.of()).messages());
  }
}
