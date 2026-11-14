package dev.chainguard.thwart.compiler.verify.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The packaged {@code thwart-compiler} jar (#365), checked after {@code package} by failsafe: the
 * engine is inlined under Thwart's own packages as Java 8 bytecode with its license, and the
 * published pom no longer names it, so a consumer's dependency tree has no htmlparser.
 */
class ShadedJarIT {

  private static Path jar() {
    Path jar = Path.of(System.getProperty("thwart.compiler.jar", ""));
    assertTrue(Files.isRegularFile(jar), "no thwart-compiler jar at " + jar);
    return jar;
  }

  @Test
  void engineIsInlinedUnderThwartPackagesAsJava8() throws IOException {
    List<String> outside = new ArrayList<>();
    List<String> tooNew = new ArrayList<>();
    int engineClasses = 0;
    try (JarFile jar = new JarFile(jar().toFile())) {
      for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
        JarEntry e = en.nextElement();
        String name = e.getName();
        if (!name.startsWith("dev/chainguard/thwart/") && !name.startsWith("META-INF/")
            && !name.equals("dev/") && !name.equals("dev/chainguard/")) {
          outside.add(name);
        }
        if (name.endsWith(".class")) {
          if (name.startsWith("dev/chainguard/thwart/compiler/internal/html5/")) {
            engineClasses++;
            assertTrue(name.startsWith("dev/chainguard/thwart/compiler/internal/html5/current/"), "only the current engine: " + name);
          }
          try (InputStream in = jar.getInputStream(e)) {
            byte[] head = in.readNBytes(8);
            int major = ((head[6] & 0xff) << 8) | (head[7] & 0xff);
            if (major > 52) {
              tooNew.add(name + " (" + major + ")");
            }
          }
        }
      }
      assertEquals(List.of(), outside, "entries outside dev/chainguard/thwart and META-INF (no nu/validator)");
      assertEquals(List.of(), tooNew, "classes newer than Java 8");
      assertTrue(engineClasses > 50, "engine classes: " + engineClasses);
      assertTrue(jar.getEntry("dev/chainguard/thwart/compiler/internal/html5/current/impl/Tokenizer.class") != null);
      List<String> licenses = new ArrayList<>();
      for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
        String name = en.nextElement().getName();
        if (name.startsWith("META-INF/LICENSE-validator-htmlparser-")) {
          licenses.add(name);
        }
      }
      assertEquals(List.of("META-INF/LICENSE-validator-htmlparser-26.10.12.txt"), licenses);
      try (InputStream in = jar.getInputStream(jar.getJarEntry(licenses.get(0)))) {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("Permission is hereby granted, free of charge"), text);
        assertTrue(text.contains("Mozilla Foundation"), text);
      }
    }
    long size = Files.size(jar());
    System.out.println("thwart-compiler jar: " + size + " bytes");
    assertTrue(size < 1024 * 1024, "jar is " + size + " bytes; the target is under 1 MB");
  }

  /** The reduced pom is what consumers resolve: no engine module, validator.nu only in test scope. */
  @Test
  void publishedPomHasNoEngineDependency() throws IOException {
    Path pom = Path.of(System.getProperty("thwart.compiler.pom", ""));
    assertTrue(Files.isRegularFile(pom), "no dependency-reduced pom at " + pom);
    String xml = Files.readString(pom);
    Matcher m = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(xml);
    int dependencies = 0;
    while (m.find()) {
      dependencies++;
      String d = m.group(1);
      assertTrue(!d.contains("thwart-html5"), "engine modules are shaded, not dependencies: " + d);
      boolean test = d.contains("<scope>test</scope>");
      assertTrue(!d.contains("attoparser"), "attoparser is no dependency of the compiler, not even in tests: " + d);
      if (d.contains("<groupId>nu.validator</groupId>")) {
        assertTrue(test, "only test scope: " + d);
      }
      if (!test) {
        assertTrue(d.contains("<artifactId>thwart-runtime</artifactId>"), "the only non-test dependency is the runtime: " + d);
      }
    }
    assertTrue(dependencies > 0, xml);
  }
}
