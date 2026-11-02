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
        compile(dir.resolve("i18n/messages_en").toString(), "<div><div t:utext=\"#{intro}\">x</div><h1 t:text=\"#{title}\">x</h1></div>")
            .assertSuccess();
    // The HTML message is sanitized by the message policy (phrasing only, #677).
    RenderContext de =
        new RenderContext(Locale.GERMAN, (k, a) -> "<p>Bitte <b>speichern</b></p>", new DefaultLinks(""), Forms.NONE);
    assertEquals(
        "<div><div>Bitte <b>speichern</b></div><h1>&lt;p&gt;Bitte &lt;b&gt;speichern&lt;/b&gt;&lt;/p&gt;</h1></div>",
        c.render("page", Map.of(), de));
  }

  /**
   * A template with the HTML message {@code key} inside a link: it compiles only if no loaded
   * bundle file has a link in the key's value (#677), so it shows which files are loaded.
   */
  private static String inLink(String key) {
    return "<div>\n<a href=\"/\"><span t:utext=\"#{" + key + "}\">x</span></a></div>";
  }

  @Test
  void linkInAnotherLocaleOfALocaleSuffixedBaseCounts() throws IOException {
    espd();
    compile(dir.resolve("i18n/messages_en").toString(), inLink("intro")).assertSuccess();
    write("i18n/messages_fr.properties", "intro=<a href=\"/fr\">Salut</a>\n");
    compile(dir.resolve("i18n/messages_en").toString(), inLink("intro"))
        .assertError("page.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
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
    write("i18n/messages_en-US.properties", "intro=<a href=\"/us\">x</a><script>alert(1)</script>\n");
    write("i18n/other.properties", "x=y\n");
    Compilation c = compile(dir.resolve("i18n/messages_en").toString(), inLink("intro")).assertSuccess();
    List<String> warnings =
        c.diagnostics().stream().filter(d -> d.startsWith("WARNING: Tessera: message file")).toList();
    assertEquals(1, warnings.size(), c.diagnostics().toString());
    assertTrue(warnings.get(0).contains("messages_en-US.properties is not loaded"), warnings.get(0));
    // Spring may still serve the skipped file's value; the message policy without links cleans it.
    RenderContext us = new RenderContext(
        Locale.US, (k, a) -> "<a href=\"/us\">x</a><script>alert(1)</script>", new DefaultLinks(""), Forms.NONE);
    assertEquals("<div>\n<a href=\"/\"><span>x</span></a></div>", c.render("page", Map.of(), us));
  }

  @Test
  void scriptLocaleFileIsLoaded() throws IOException {
    espd();
    write("i18n/messages_zh_Hant_TW.properties", "intro=<a href=\"/zh\">Hi</a>\n");
    compile(dir.resolve("i18n/messages_en").toString(), inLink("intro"))
        .assertError("page.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
  }

  @Test
  void sameKeyInTwoBasenamesCountsBothValues() throws IOException {
    write("a/messages.properties", "k=<b>A</b>\n");
    write("b/labels.properties", "k=<b>B</b>\n");
    Compilation c = compile(dir.resolve("a/messages") + "," + dir.resolve("b/labels"), inLink("k")).assertSuccess();
    for (String v : List.of("<b>A</b>", "<b>B</b>")) {
      RenderContext ctx = new RenderContext(Locale.ENGLISH, (k, a) -> v, new DefaultLinks(""), Forms.NONE);
      assertEquals("<div>\n<a href=\"/\"><span>" + v + "</span></a></div>", c.render("page", Map.of(), ctx));
    }
    write("b/labels_fr.properties", "k=<a href=\"/fr\">B</a>\n");
    compile(dir.resolve("a/messages") + "," + dir.resolve("b/labels"), inLink("k"))
        .assertError("page.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
  }

  @Test
  void noWarningWhenEveryFileIsALocale() throws IOException {
    espd();
    Compilation c = compile(dir.resolve("i18n/messages_en").toString(), "<p>x</p>").assertSuccess();
    assertTrue(c.diagnostics().stream().noneMatch(d -> d.contains("message file")), c.diagnostics().toString());
  }

  private List<String> warnings(Compilation c, String start) {
    return c.diagnostics().stream().filter(d -> d.startsWith("WARNING: ") && d.contains(start)).toList();
  }

  /** #271: a suffix whose language is not a language code is not a locale. */
  @Test
  void suffixWithoutALanguageIsNotALocale() throws IOException {
    write("messages.properties", "k=<b>Hi</b>\n");
    write("messages_faq.properties", "k=<a href=\"/faq\">Hi</a>\n");
    Compilation c = compile(dir.resolve("messages").toString(), inLink("k")).assertSuccess();
    List<String> skipped = warnings(c, "Tessera: message file");
    assertEquals(1, skipped.size(), c.diagnostics().toString());
    assertTrue(skipped.get(0).contains("messages_faq.properties is not loaded"), skipped.get(0));
  }

  /** #271: a three-letter language the JDK knows (Filipino) is still a locale, and is loaded. */
  @Test
  void threeLetterLanguageIsALocale() throws IOException {
    write("messages.properties", "k=<b>Hi</b>\n");
    write("messages_fil.properties", "k=<a href=\"/fil\">Kumusta</a>\n");
    compile(dir.resolve("messages").toString(), inLink("k")).assertError("page.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
  }

  /** #271: Spring names a variant without a country {@code _en__POSIX}; that file is loaded. */
  @Test
  void variantAfterAnEmptyCountryIsALocale() throws IOException {
    write("messages.properties", "k=<b>Hi</b>\n");
    write("messages_en__POSIX.properties", "k=<a href=\"/posix\">Hi</a>\n");
    Compilation c = compile(dir.resolve("messages").toString(), inLink("k"));
    c.assertError("page.html:2:", "[verifier:RAW_CLOSES_ELEMENT]");
    assertEquals(List.of(), warnings(c, "Tessera: message file"));
  }

  /** #271: Spring never reads {@code _de_formal} (its name for that locale is {@code _de__formal}). */
  @Test
  void variantWithoutACountrySeparatorIsNotALocale() throws IOException {
    write("messages.properties", "k=<b>Hi</b>\n");
    write("messages_de_formal.properties", "k=<a href=\"/formal\">Hi</a>\n");
    write("messages_en_US_POSIX.properties", "k=<b>Hi</b>\n");
    Compilation c = compile(dir.resolve("messages").toString(), inLink("k")).assertSuccess();
    List<String> skipped = warnings(c, "Tessera: message file");
    assertEquals(1, skipped.size(), c.diagnostics().toString());
    assertTrue(skipped.get(0).contains("messages_de_formal.properties is not loaded"), skipped.get(0));
  }

  /** #271: with default locale pt_BR, Spring falls back to messages_pt too. */
  @Test
  void defaultLocaleFallsBackToItsLanguageFile() throws IOException {
    write("messages.properties", "title=T\n");
    write("messages_pt_BR.properties", "title=T\nbr=x\n");
    write("messages_pt.properties", "pt=x\n");
    write("messages_fr.properties", "title=T\npt=x\nfr=x\n");
    String option = dir.resolve("messages_pt_BR").toString();
    Compilation c = compile(option, "<div><p t:text=\"#{pt}\">x</p><p t:text=\"#{br}\">x</p></div>").assertSuccess();
    compile(option, "<p t:text=\"#{fr}\">x</p>")
        .assertError("unknown message key 'fr'", "messages_pt_BR.properties", "messages_pt.properties", "it is only in");
    // A key in the pt_BR file is missing for pt and fr requests under ResourceBundleMessageSource.
    List<String> gaps = warnings(c, "message key");
    assertEquals(1, gaps.size(), c.diagnostics().toString());
    assertTrue(gaps.get(0).contains("message key 'br'") && gaps.get(0).contains("messages_fr.properties, messages_pt.properties"), gaps.get(0));
  }

  /**
   * #271: Boot's ResourceBundleMessageSource does not fall back to the default locale for a
   * locale with its own file, so a key only the default locale has is a warning naming that file.
   */
  @Test
  void warnsWhereResourceBundleMessageSourceWouldNotFindTheKey() throws IOException {
    espd(); // intro is in _en and _de, not in the base file
    write("i18n/messages_fr.properties", "title=Titre\n");
    write("i18n/messages_de_AT.properties", "title=Titel\n");
    Compilation c =
        compile(
                dir.resolve("i18n/messages_en").toString(),
                "<div><div t:utext=\"#{intro}\">x</div><div t:utext=\"#{intro}\">x</div><h1 t:text=\"#{title}\">x</h1></div>")
            .assertSuccess();
    List<String> gaps = warnings(c, "message key");
    // Once per key; de_AT falls back to _de, which has it; title is in the base file.
    assertEquals(1, gaps.size(), c.diagnostics().toString());
    assertTrue(gaps.get(0).contains("message key 'intro'") && gaps.get(0).contains("in messages_fr.properties (or"), gaps.get(0));
    assertTrue(gaps.get(0).contains("ReloadableResourceBundleMessageSource does"), gaps.get(0));
  }

  @Test
  void noFallbackWarningWhenEveryLocaleHasTheKey() throws IOException {
    espd();
    Compilation c = compile(dir.resolve("i18n/messages_en").toString(), "<div t:utext=\"#{intro}\">x</div>").assertSuccess();
    assertEquals(List.of(), warnings(c, "message key"));
  }

  @Test
  void optionIsACommaSeparatedList() {
    assertEquals(
        List.of(Paths.get("/a/messages"), Paths.get("/b/Validation_en")),
        ProcessorOptions.parse(Map.of(ProcessorOptions.MESSAGES, " /a/messages, ,/b/Validation_en ")).messages());
    assertEquals(List.of(), ProcessorOptions.parse(Map.of()).messages());
  }
}
