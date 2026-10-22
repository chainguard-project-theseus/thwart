package dev.tessera.migrate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The migration audit: every construct that was not converted, or needs a manual edit. */
final class Report {

  /** One report entry; {@code file} is the template path relative to the input root, with '/'. */
  record Item(String file, int line, String message) {}

  private final List<Item> items = new ArrayList<>();
  private int templates;

  void addTemplate(List<Item> fileItems) {
    templates++;
    items.addAll(fileItems);
  }

  int templates() {
    return templates;
  }

  List<Item> items() {
    return List.copyOf(items);
  }

  /** Markdown, grouped by file (sorted), items in line order. */
  String toMarkdown() {
    StringBuilder sb = new StringBuilder("# tessera-migrate report\n\n");
    sb.append("Converted ").append(templates).append(" template(s); ")
        .append(items.size()).append(" item(s) need manual attention.\n");
    Map<String, List<Item>> byFile = new LinkedHashMap<>();
    items.stream()
        .sorted(Comparator.comparing(Item::file).thenComparingInt(Item::line))
        .forEach(i -> byFile.computeIfAbsent(i.file(), f -> new ArrayList<>()).add(i));
    for (Map.Entry<String, List<Item>> e : byFile.entrySet()) {
      sb.append("\n## ").append(e.getKey()).append("\n\n");
      for (Item i : e.getValue()) {
        sb.append("- `").append(i.file()).append(':').append(i.line()).append("`: ")
            .append(i.message()).append('\n');
      }
    }
    return sb.toString();
  }
}
