package dev.chainguard.thwart.compiler.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the records in a test source into the Java 8 classes an application on the Java 8
 * floor would write instead (#154): a {@code @TemplateView} final class with a private final
 * field, a {@code getX()} getter (and the record's {@code x()} accessor) per component and the
 * canonical constructor. The record body is
 * kept. Used by {@link Compilation} for {@code --release 8} runs so existing test models compile
 * unchanged. Handles the shapes the tests use, not every record: no compact constructors.
 */
final class Java8Records {
  private Java8Records() {}

  private static final Pattern RECORD = Pattern.compile("\\brecord\\s+([A-Z]\\w*)\\s*(<[^>(]*>)?\\s*\\(");
  private static final String TEMPLATE_VIEW = "@dev.chainguard.thwart.runtime.TemplateView";

  static String desugar(String source) {
    String s = source;
    Matcher m = RECORD.matcher(s);
    while (m.find()) {
      s = rewrite(s, m);
      m = RECORD.matcher(s);
    }
    return s;
  }

  private static String rewrite(String s, Matcher m) {
    String name = m.group(1);
    String typeParams = m.group(2) == null ? "" : m.group(2);
    int open = m.end() - 1;
    int close = matching(s, open, '(', ')');
    List<String[]> components = new ArrayList<>();
    for (String c : splitTopLevel(s.substring(open + 1, close))) {
      c = c.trim();
      if (c.isEmpty()) {
        continue;
      }
      int sp = c.lastIndexOf(' ');
      components.add(new String[] {c.substring(0, sp).trim(), c.substring(sp + 1)});
    }
    int bodyOpen = s.indexOf('{', close);
    String header = s.substring(close + 1, bodyOpen);
    int bodyClose = matching(s, bodyOpen, '{', '}');
    String body = s.substring(bodyOpen + 1, bodyClose);

    boolean nested = depth(s, m.start()) > 0;
    boolean annotated = s.substring(Math.max(0, m.start() - 80), m.start()).contains("TemplateView");
    StringBuilder out = new StringBuilder();
    out.append(annotated ? "" : TEMPLATE_VIEW + " ").append(nested ? "static " : "").append("final class ")
        .append(name).append(typeParams).append(header).append("{");
    StringBuilder params = new StringBuilder();
    StringBuilder assigns = new StringBuilder();
    for (String[] c : components) {
      String fieldType = c[0].endsWith("...") ? c[0].substring(0, c[0].length() - 3) + "[]" : c[0];
      out.append(" private final ").append(fieldType).append(' ').append(c[1]).append(';');
      out.append(" public ").append(fieldType).append(" get").append(Character.toUpperCase(c[1].charAt(0)))
          .append(c[1].substring(1)).append("() { return ").append(c[1]).append("; }");
      out.append(" public ").append(fieldType).append(' ').append(c[1]).append("() { return ").append(c[1]).append("; }");
      params.append(params.length() == 0 ? "" : ", ").append(c[0]).append(' ').append(c[1]);
      assigns.append(" this.").append(c[1]).append(" = ").append(c[1]).append(';');
    }
    out.append(" public ").append(name).append('(').append(params).append(") {").append(assigns).append(" }");
    out.append(body).append('}');
    return s.substring(0, m.start()) + out + s.substring(bodyClose + 1);
  }

  private static int matching(String s, int open, char o, char c) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      if (s.charAt(i) == o) {
        depth++;
      } else if (s.charAt(i) == c && --depth == 0) {
        return i;
      }
    }
    throw new IllegalArgumentException("unbalanced " + o + " in test source:\n" + s);
  }

  private static int depth(String s, int at) {
    int depth = 0;
    for (int i = 0; i < at; i++) {
      depth += s.charAt(i) == '{' ? 1 : s.charAt(i) == '}' ? -1 : 0;
    }
    return depth;
  }

  private static List<String> splitTopLevel(String s) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '<' || ch == '(') {
        depth++;
      } else if (ch == '>' || ch == ')') {
        depth--;
      } else if (ch == ',' && depth == 0) {
        parts.add(s.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(s.substring(start));
    return parts;
  }
}
