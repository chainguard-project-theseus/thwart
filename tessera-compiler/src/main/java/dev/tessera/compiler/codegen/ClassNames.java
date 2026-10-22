package dev.tessera.compiler.codegen;

/**
 * View name to generated class name: directories become lower-case sub-packages of the base
 * package, the file name becomes UpperCamelCase plus {@code Template}. Characters that are not
 * Java identifier parts are dropped with the next letter upper-cased; segments that are Java
 * keywords or start with a digit get a {@code _} prefix. Examples (base {@code tessera.generated}):
 * {@code welcome} becomes {@code tessera.generated.WelcomeTemplate};
 * {@code owners/createOrUpdateOwnerForm} becomes
 * {@code tessera.generated.owners.CreateOrUpdateOwnerFormTemplate};
 * {@code fragments/main-layout} becomes {@code tessera.generated.fragments.MainLayoutTemplate}.
 */
public final class ClassNames {
  private ClassNames() {}

  public static String className(String basePackage, String viewName) {
    throw new UnsupportedOperationException("TODO(#5): ClassNames.className");
  }
}
