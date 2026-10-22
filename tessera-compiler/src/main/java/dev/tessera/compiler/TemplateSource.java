package dev.tessera.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One template file.
 *
 * @param viewName path relative to the templates root without {@code .html}, using {@code /}
 *     (e.g. {@code owners/ownerDetails}); this is the name controllers return
 * @param path path relative to the templates root, used in diagnostics
 * @param content the file contents (UTF-8 decoded)
 */
public final class TemplateSource {
  private final String viewName;
  private final String path;
  private final String content;

  public TemplateSource(String viewName, String path, String content) {
    this.viewName = viewName;
    this.path = path;
    this.content = content;
  }

  public String viewName() {
    return viewName;
  }

  public String path() {
    return path;
  }

  public String content() {
    return content;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TemplateSource)) {
      return false;
    }
    TemplateSource that = (TemplateSource) o;
    return Objects.equals(viewName, that.viewName)
        && Objects.equals(path, that.path)
        && Objects.equals(content, that.content);
  }

  @Override
  public int hashCode() {
    int h = Objects.hashCode(viewName);
    h = 31 * h + Objects.hashCode(path);
    h = 31 * h + Objects.hashCode(content);
    return h;
  }

  @Override
  public String toString() {
    return "TemplateSource[viewName=" + viewName + ", path=" + path + ", content=" + content + "]";
  }

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
