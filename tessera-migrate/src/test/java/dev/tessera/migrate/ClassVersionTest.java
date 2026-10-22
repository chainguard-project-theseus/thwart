package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.attoparser.MarkupParser;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * The shaded {@code tessera-migrate} jar runs with {@code java -jar} on JDK 8 (#167), so every
 * class that goes into it (this module's and the bundled jsoup and attoparser) must be class-file
 * major version 52 or lower. Classes under {@code META-INF/versions/} are skipped: JDK 8 never
 * loads them, and the shaded jar's manifest is not {@code Multi-Release}, so no JDK does.
 */
class ClassVersionTest {

  private static final int JAVA_8 = 52;

  @Test
  void ownClassesAreJava8() throws IOException {
    Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
    List<String> tooNew = new ArrayList<>();
    int count = 0;
    try (Stream<Path> files = Files.walk(classes)) {
      for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
        count++;
        try (InputStream in = Files.newInputStream(p)) {
          check(classes.relativize(p).toString(), in, tooNew);
        }
      }
    }
    assertTrue(count > 10, "found the module's classes in " + classes);
    assertEquals(List.of(), tooNew);
  }

  @Test
  void bundledDependenciesAreJava8() throws IOException {
    for (Class<?> dep : List.of(Jsoup.class, MarkupParser.class)) {
      Path jar = Path.of(dep.getProtectionDomain().getCodeSource().getLocation().getPath());
      List<String> tooNew = new ArrayList<>();
      int count = 0;
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
          ZipEntry entry = e.nextElement();
          String name = entry.getName();
          if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")
              || name.equals("module-info.class")) { // the shade plugin drops module-info
            continue;
          }
          count++;
          try (InputStream in = zip.getInputStream(entry)) {
            check(jar.getFileName() + "!" + name, in, tooNew);
          }
        }
      }
      assertTrue(count > 10, "found classes in " + jar);
      assertEquals(List.of(), tooNew, jar.getFileName().toString());
    }
  }

  private static void check(String name, InputStream in, List<String> tooNew) throws IOException {
    DataInputStream data = new DataInputStream(in);
    assertEquals(0xCAFEBABE, data.readInt(), name);
    data.readUnsignedShort(); // minor
    int major = data.readUnsignedShort();
    if (major > JAVA_8) {
      tooNew.add(name + " (major " + major + ")");
    }
  }
}
