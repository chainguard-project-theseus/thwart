package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClassNamesTest {
  private static final String BASE = "tessera.generated";

  @Test
  void documentedExamples() {
    assertEquals("tessera.generated.WelcomeTemplate", ClassNames.className(BASE, "welcome"));
    assertEquals(
        "tessera.generated.owners.CreateOrUpdateOwnerFormTemplate",
        ClassNames.className(BASE, "owners/createOrUpdateOwnerForm"));
    assertEquals("tessera.generated.fragments.MainLayoutTemplate", ClassNames.className(BASE, "fragments/main-layout"));
  }

  @Test
  void hyphensDotsAndSpacesUpperCaseTheNextLetter() {
    assertEquals("tessera.generated.MyPageV2Template", ClassNames.className(BASE, "my-page.v2"));
    assertEquals("tessera.generated.AbCTemplate", ClassNames.className(BASE, "ab c"));
    assertEquals("tessera.generated.ErrorTemplate", ClassNames.className(BASE, "error"));
  }

  @Test
  void nestedDirectoriesBecomeLowerCasePackages() {
    assertEquals(
        "tessera.generated.admin.usermgmt.ListTemplate", ClassNames.className(BASE, "admin/user-Mgmt/list"));
  }

  @Test
  void keywordsAndDigitsArePrefixed() {
    assertEquals("tessera.generated._class._404Template", ClassNames.className(BASE, "class/404"));
    assertEquals("tessera.generated._2024.IndexTemplate", ClassNames.className(BASE, "2024/index"));
    assertEquals("tessera.generated._new.ClassTemplate", ClassNames.className(BASE, "new/class"));
  }

  @Test
  void nonIdentifierCharactersAreDropped() {
    assertEquals("tessera.generated.a.BTemplate",ClassNames.className(BASE, "a$/b"));
    assertEquals("tessera.generated.CafTemplate", ClassNames.className(BASE, "café"));
  }

  @Test
  void unnamedBasePackage() {
    assertEquals("WelcomeTemplate", ClassNames.className("", "welcome"));
    assertEquals("x.WelcomeTemplate", ClassNames.className("", "x/welcome"));
  }
}
