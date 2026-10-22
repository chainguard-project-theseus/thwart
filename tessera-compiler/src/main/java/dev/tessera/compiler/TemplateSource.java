package dev.tessera.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * One template file.
 *
 * @param viewName path relative to the templates root without {@code .html}, using {@code /}
 *     (e.g. {@code owners/ownerDetails}); this is the name controllers return
 * @param path path relative to the templates root, used in diagnostics
 * @param content the file contents (UTF-8 decoded)
 */
public record TemplateSource(String viewName, String path, String content) {

  /** Offset in {@link #content} of the first character of each line; index 0 is line 1. */
  public int[] lineStarts() {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }
}
