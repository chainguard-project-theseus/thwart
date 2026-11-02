package dev.tessera.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.runtime.Template;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The public API of {@code tessera-runtime} and this starter is the checked-in, sorted
 * {@code public-api.txt} at the repository root: every public or protected type, with its
 * supertypes, and every public or protected constructor, method and field. A change to that
 * surface fails here until the file is updated, so it is reviewed. The actual list is written to
 * {@code target/public-api.txt}. Types in a {@code .internal} package are listed in their own
 * section: public only so that generated code and Tessera's modules can call them, not API.
 */
class PublicApiTest {
  private static final Path CHECKED_IN = Paths.get("../public-api.txt");

  @Test
  void publicApiIsTheCheckedInList() throws Exception {
    List<String> api = new ArrayList<>();
    List<String> internal = new ArrayList<>();
    for (Class<?> anchor : new Class<?>[] {Template.class, TesseraAutoConfiguration.class}) {
      for (String name : classNames(anchor)) {
        Class<?> c = Class.forName(name, false, anchor.getClassLoader());
        if (isApi(c)) {
          describe(c, name.contains(".internal.") ? internal : api);
        }
      }
    }
    Collections.sort(api);
    Collections.sort(internal);
    String actual =
        "# Public API: the types apps and templates use. Signatures that name an internal type\n"
            + "# (Output, StringOutput, TemplateEntry) are for generated code and the starter.\n"
            + String.join("\n", api)
            + "\n\n# Internal (dev.tessera.runtime.internal): called by generated code only; not API.\n"
            + String.join("\n", internal)
            + "\n";
    Files.write(Paths.get("target/public-api.txt"), actual.getBytes(StandardCharsets.UTF_8));
    String expected = new String(Files.readAllBytes(CHECKED_IN), StandardCharsets.UTF_8);
    assertEquals(
        expected,
        actual,
        "the public API changed: review it, then cp tessera-spring-boot-starter/target/public-api.txt public-api.txt");
  }

  /** The classes under {@code dev/tessera/} in the jar or directory {@code anchor} was loaded from. */
  private static List<String> classNames(Class<?> anchor) throws Exception {
    File root = new File(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    List<String> files = new ArrayList<>();
    if (root.isDirectory()) {
      try (Stream<Path> walk = Files.walk(root.toPath())) {
        walk.forEach(p -> files.add(root.toPath().relativize(p).toString().replace(File.separatorChar, '/')));
      }
    } else {
      try (JarFile jar = new JarFile(root)) {
        for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
          files.add(e.nextElement().getName());
        }
      }
    }
    return files.stream()
        .filter(f -> f.startsWith("dev/tessera/") && f.endsWith(".class") && !f.endsWith("-info.class"))
        .map(f -> f.substring(0, f.length() - ".class".length()).replace('/', '.'))
        .collect(Collectors.toList());
  }

  /** A public or protected type that is not local, anonymous or synthetic, in an API type if nested. */
  private static boolean isApi(Class<?> c) {
    if (c.isAnonymousClass() || c.isLocalClass() || c.isSynthetic() || !visible(c.getModifiers())) {
      return false;
    }
    return c.getDeclaringClass() == null || isApi(c.getDeclaringClass());
  }

  private static boolean visible(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static void describe(Class<?> c, List<String> api) {
    String prefix = c.getName() + ": ";
    StringBuilder type = new StringBuilder(c.toGenericString());
    Type superclass = c.getGenericSuperclass();
    if (superclass != null && superclass != Object.class && !c.isEnum()) {
      type.append(" extends ").append(superclass.getTypeName());
    }
    Type[] interfaces = c.getGenericInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      type.append(i == 0 ? (c.isInterface() ? " extends " : " implements ") : ", ").append(interfaces[i].getTypeName());
    }
    api.add(prefix + type);
    List<Member> members = new ArrayList<>();
    Collections.addAll(members, c.getDeclaredConstructors());
    Collections.addAll(members, c.getDeclaredMethods());
    Collections.addAll(members, c.getDeclaredFields());
    for (Member m : members) {
      if (!visible(m.getModifiers()) || m.isSynthetic() || (m instanceof Method && ((Method) m).isBridge())) {
        continue;
      }
      String s =
          m instanceof Method
              ? ((Method) m).toGenericString()
              : m instanceof Constructor ? ((Constructor<?>) m).toGenericString() : ((Field) m).toGenericString();
      api.add(prefix + s.replace(" " + c.getTypeName() + ".", " "));
    }
  }
}
