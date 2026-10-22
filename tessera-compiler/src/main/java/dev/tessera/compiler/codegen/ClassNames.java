package dev.tessera.compiler.codegen;

import java.util.Locale;
import javax.lang.model.SourceVersion;

/**
 * View name to generated class name: directories become lower-case sub-packages of the base
 * package, the file name becomes UpperCamelCase plus {@code Template}. Characters other than
 * ASCII letters, digits and {@code _} are dropped with the next letter upper-cased; segments
 * that are Java keywords or start with a digit get a {@code _} prefix. Examples (base {@code tessera.generated}):
 * {@code welcome} becomes {@code tessera.generated.WelcomeTemplate};
 * {@code owners/createOrUpdateOwnerForm} becomes
 * {@code tessera.generated.owners.CreateOrUpdateOwnerFormTemplate};
 * {@code fragments/main-layout} becomes {@code tessera.generated.fragments.MainLayoutTemplate};
 * {@code class/404} becomes {@code tessera.generated._class._404Template}.
 */
final class ClassNames {
  private ClassNames() {}

  /** Simple name of the generated registry class in the base package. */
  static final String REGISTRY = "TesseraRegistry";

  static String className(String basePackage, String viewName) {
    String[] segments = viewName.split("/", -1);
    String name = basePackage;
    for (int i = 0; i < segments.length - 1; i++) {
      name = qualify(name, guard(camel(segments[i]).toLowerCase(Locale.ROOT)));
    }
    String file = camel(segments[segments.length - 1]);
    if (!file.isEmpty()) {
      file = Character.toUpperCase(file.charAt(0)) + file.substring(1);
    }
    return qualify(name, guard(file + "Template"));
  }

  /** {@code pkg.simple}, or {@code simple} in the unnamed package. */
  static String qualify(String pkg, String simple) {
    return pkg.isEmpty() ? simple : pkg + "." + simple;
  }

  /** Drops characters that are not identifier parts, upper-casing the letter after each. */
  private static String camel(String segment) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      if (c < 128 && (Character.isLetterOrDigit(c) || c == '_')) {
        sb.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      } else {
        upper = sb.length() > 0;
      }
    }
    return sb.toString();
  }

  /** Prefixes {@code _} to keywords, empty segments and segments starting with a digit. */
  private static String guard(String s) {
    if (s.isEmpty() || Character.isDigit(s.charAt(0)) || SourceVersion.isKeyword(s)) {
      return "_" + s;
    }
    return s;
  }
}
