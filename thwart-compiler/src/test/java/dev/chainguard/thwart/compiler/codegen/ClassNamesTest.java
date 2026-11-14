package dev.chainguard.thwart.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClassNamesTest {
  private static final String BASE = "thwart.generated";

  @Test
  void documentedExamples() {
    assertEquals("thwart.generated.WelcomeTemplate", ClassNames.className(BASE, "welcome"));
    assertEquals(
        "thwart.generated.owners.CreateOrUpdateOwnerFormTemplate",
        ClassNames.className(BASE, "owners/createOrUpdateOwnerForm"));
    assertEquals("thwart.generated.fragments.MainLayoutTemplate", ClassNames.className(BASE, "fragments/main-layout"));
  }

  @Test
  void hyphensDotsAndSpacesUpperCaseTheNextLetter() {
    assertEquals("thwart.generated.MyPageV2Template", ClassNames.className(BASE, "my-page.v2"));
    assertEquals("thwart.generated.AbCTemplate", ClassNames.className(BASE, "ab c"));
    assertEquals("thwart.generated.ErrorTemplate", ClassNames.className(BASE, "error"));
  }

  @Test
  void nestedDirectoriesBecomeLowerCasePackages() {
    assertEquals(
        "thwart.generated.admin.usermgmt.ListTemplate", ClassNames.className(BASE, "admin/user-Mgmt/list"));
  }

  @Test
  void keywordsAndDigitsArePrefixed() {
    assertEquals("thwart.generated._class._404Template", ClassNames.className(BASE, "class/404"));
    assertEquals("thwart.generated._2024.IndexTemplate", ClassNames.className(BASE, "2024/index"));
    assertEquals("thwart.generated._new.ClassTemplate", ClassNames.className(BASE, "new/class"));
  }

  @Test
  void nonIdentifierCharactersAreDropped() {
    assertEquals("thwart.generated.a.BTemplate",ClassNames.className(BASE, "a$/b"));
    assertEquals("thwart.generated.CafTemplate", ClassNames.className(BASE, "café"));
  }

  @Test
  void unnamedBasePackage() {
    assertEquals("WelcomeTemplate", ClassNames.className("", "welcome"));
    assertEquals("x.WelcomeTemplate", ClassNames.className("", "x/welcome"));
  }
}
