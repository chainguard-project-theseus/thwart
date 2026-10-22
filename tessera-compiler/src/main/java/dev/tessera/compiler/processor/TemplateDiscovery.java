package dev.tessera.compiler.processor;

import dev.tessera.compiler.TemplateSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds every {@code *.html} file under the templates root, in a stable (sorted) order. */
public final class TemplateDiscovery {
  private TemplateDiscovery() {}

  public static List<TemplateSource> discover(Path root) throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.walk(root)) {
      files = s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".html")).sorted().toList();
    }
    List<TemplateSource> out = new ArrayList<>();
    for (Path f : files) {
      String rel = root.relativize(f).toString().replace(f.getFileSystem().getSeparator(), "/");
      String viewName = rel.substring(0, rel.length() - ".html".length());
      out.add(new TemplateSource(viewName, rel, Files.readString(f, StandardCharsets.UTF_8)));
    }
    return out;
  }
}
