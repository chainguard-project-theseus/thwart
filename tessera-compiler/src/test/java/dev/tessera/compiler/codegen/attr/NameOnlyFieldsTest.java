package dev.tessera.compiler.codegen.attr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Escapers;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.Messages;
import dev.tessera.runtime.RenderContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fields whose type is never view-safe, such as a {@code MultipartFile}, can be bound by name where
 * the value is never read (#129): {@code t:field} on a file or password input, {@code t:errors},
 * {@code t:classappend} and {@code fields.*}. Anything that would read the value is still rejected.
 */
class NameOnlyFieldsTest {
  private static final String HEAD =
      "<form xmlns:t=\"https://tessera.dev\" t:import=\"com.example.*\" t:params=\"Upload upload\""
          + " t:object=\"upload\">";

  /** Spring's {@code MultipartFile}, on the deny-list as an {@code org.springframework.core.io} type. */
  private static Compilation compile(String body) {
    return Compilation.builder()
        .java(
            "org.springframework.core.io.InputStreamSource",
            "package org.springframework.core.io; public interface InputStreamSource {"
                + " java.io.InputStream getInputStream(); }")
        .java(
            "org.springframework.web.multipart.MultipartFile",
            "package org.springframework.web.multipart; public interface MultipartFile extends"
                + " org.springframework.core.io.InputStreamSource { String getOriginalFilename(); }")
        .java(
            "com.example.Upload",
            "package com.example; import org.springframework.web.multipart.MultipartFile; import java.util.List;"
                + " public record Upload(String title, MultipartFile file, List<MultipartFile> attachments) {}")
        .template("form.html", HEAD + body + "</form>")
        .compile();
  }

  private static String render(Compilation c, Map<String, List<String>> errors) {
    Forms forms =
        new Forms() {
          @Override
          public String value(String objectName, String path, Object currentValue) {
            return Escapers.toText(currentValue);
          }

          @Override
          public List<String> errors(String objectName, String path) {
            assertEquals("upload", objectName);
            return errors.getOrDefault(path, List.of());
          }

          @Override
          public Optional<CsrfToken> csrfToken() {
            return Optional.empty();
          }
        };
    Map<String, Object> model = new HashMap<>();
    model.put("upload", c.newInstance("com.example.Upload", "t", null, List.of()));
    return c.render("form", model, new RenderContext(Locale.ROOT, Messages.NONE, new DefaultLinks(""), forms));
  }

  @Test
  void fileInputErrorsAndFieldsCompileWithoutReadingTheValue() {
    Compilation c =
        compile(
            "<input type=\"file\" class=\"in\" t:classappend=\"fields.hasErrors(*{file}) ? 'bad'\" t:field=\"*{file}\">"
                + "<span t:errors=\"*{file}\"></span>"
                + "<input type=\"FILE\" t:field=\"*{attachments[0]}\">"
                + "<p t:if=\"${fields.hasErrors('attachments')}\" t:text=\"${fields.errors('attachments')}\"></p>"
                + "<i t:if=\"${fields.hasErrors(*{attachments[0]})}\">x</i>"
                + "<input type=\"password\" value=\"v\" t:field=\"*{file}\">")
            .assertSuccess();
    assertEquals(
        "<form><input type=\"file\" class=\"in\" id=\"file\" name=\"file\">"
            + "<input type=\"FILE\" id=\"attachments0\" name=\"attachments[0]\">"
            + "<input type=\"password\" id=\"file\" name=\"file\"></form>",
        render(c, Map.of()));
    assertEquals(
        "<form><input type=\"file\" class=\"in bad\" id=\"file\" name=\"file\"><span>too big</span>"
            + "<input type=\"FILE\" id=\"attachments0\" name=\"attachments[0]\"><p>[none]</p><i>x</i>"
            + "<input type=\"password\" id=\"file\" name=\"file\"></form>",
        render(
            c,
            Map.of("file", List.of("too big"), "attachments", List.of("none"), "attachments[0]", List.of("empty"))));
  }

  @Test
  void generatedCodeNeverReadsTheValue() {
    Compilation c =
        compile(
                "<input type=\"file\" t:field=\"*{file}\"><span t:errors=\"*{file}\"></span>"
                    + "<p t:if=\"${fields.hasErrors('attachments')}\">x</p>")
            .assertSuccess();
    String java = c.generatedSource("tessera.generated.FormTemplate");
    assertFalse(java.contains(".file()"), java);
    assertFalse(java.contains(".attachments()"), java);
  }

  @Test
  void readingTheValueIsStillRejected() {
    String never = "MultipartFile is never view-safe";
    compile("<input type=\"text\" t:field=\"*{file}\">").assertError("form.html:1:", never);
    compile("<input t:field=\"*{file}\">").assertError(never);
    compile("<input type=\"hidden\" t:field=\"*{file}\">").assertError(never);
    compile("<input type=\"checkbox\" value=\"x\" t:field=\"*{attachments}\">").assertError(never);
    compile("<textarea t:field=\"*{file}\"></textarea>").assertError(never);
    compile("<select t:field=\"*{file}\"></select>").assertError(never);
    compile("<input type=\"file\" t:value=\"*{file}\">").assertError(never);
    compile("<p>[[*{file}]]</p>").assertError(never);
    compile("<p t:text=\"*{attachments[0]}\"></p>").assertError(never);
    compile("<p t:if=\"*{file} != null\"></p>").assertError(never);
    compile("<p t:text=\"${fields.value('file')}\"></p>").assertError(never);
  }

  @Test
  void navigatingIntoTheFieldIsStillRejected() {
    compile("<p t:if=\"${fields.hasErrors('file.originalFilename')}\"></p>")
        .assertError("cannot read 'originalFilename'", "never view-safe");
    compile("<span t:errors=\"*{file.inputStream}\"></span>").assertError("cannot read 'inputStream'");
    compile("<input type=\"file\" t:field=\"*{attachments[0].originalFilename}\">")
        .assertError("cannot read 'originalFilename'");
  }

  @Test
  void aFieldArgumentCarriesTheValueSoIsStillRejected() {
    compile(
            "<t:block t:fragment=\"f(Field name)\"><input type=\"file\" t:field=\"name\"></t:block>"
                + "<div t:replace=\"~{:: f('file')}\"></div>")
        .assertError("MultipartFile is never view-safe");
  }
}
