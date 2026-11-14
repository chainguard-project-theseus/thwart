package dev.chainguard.thwart.compiler.processor;

import dev.chainguard.thwart.compiler.TemplateSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Finds every {@code *.html} file under the templates root, in a stable (sorted) order. */
final class TemplateDiscovery {
  private TemplateDiscovery() {}

  static List<TemplateSource> discover(Path root) throws IOException {
    List<Path> files;
    try (Stream<Path> s = Files.walk(root)) {
      files = s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".html")).sorted().collect(Collectors.toList());
    }
    List<TemplateSource> out = new ArrayList<>();
    for (Path f : files) {
      String rel = root.relativize(f).toString().replace(f.getFileSystem().getSeparator(), "/");
      String viewName = rel.substring(0, rel.length() - ".html".length());
      // Like Files.readString: malformed UTF-8 is an error, not replaced.
      String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(f))).toString();
      out.add(new TemplateSource(viewName, rel, content));
    }
    return out;
  }
}
