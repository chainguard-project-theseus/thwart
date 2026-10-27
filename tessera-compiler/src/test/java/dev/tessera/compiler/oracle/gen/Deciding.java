package dev.tessera.compiler.oracle.gen;

import dev.tessera.compiler.oracle.OracleCase;
import dev.tessera.compiler.oracle.gen.Shape.Param;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cases that get past the profile to where the verifier decides (#399). The pairwise grammar
 * ({@link Generator}) mostly writes templates the profile rejects first, so few of its cases test
 * the verifier's own rules or reach the engine-drift check. This is a small product of positions
 * the profile accepts and the verifier has to judge: tables (directly in a table part, in cells and
 * captions), svg and math in table cells and the "reset the insertion mode" constructs of #398
 * (exclusion (a)), template contents, and attributes whose value the tree builder reads ({@code
 * type}, {@code encoding}, {@code font color/face/size}). The accepted ones go to the Node job,
 * which compares each engine's tree with vnu's; the rejected ones pin the error-free rule and the
 * exclusions. No context here exists only for error-recovery rules the error-free rule replaced
 * (foster parenting, reconstruction, Noah's Ark).
 *
 * <p>Separate from the pairwise enumeration, so adding a value here doesn't move the generated case
 * ids that {@code known-holes.txt} names. Order is part of the case list; append new values.
 */
public final class Deciding {
  private Deciding() {}

  /** Where the hole goes: {@code {}} in the body. */
  public record Context(String label, String body) {}

  /** What goes there. */
  public record Probe(String label, String markup) {
    boolean safeHtml() {
      return markup.contains("[(h)]");
    }
  }

  /** The #398 construct: a table inside foreign content in a cell, which vnu leaves "in body". */
  static final String RESET_SVG = "<svg><foreignObject><div><table></table></div></foreignObject></svg>";

  public static final List<Context> CONTEXTS =
      List.of(
          new Context("table", "<table>{}</table>"),
          new Context("tbody", "<table><tbody>{}</tbody></table>"),
          new Context("tr after td", "<table><tbody><tr><td>a</td>{}</tr></tbody></table>"),
          new Context("colgroup", "<table><colgroup>{}</colgroup></table>"),
          new Context("caption", "<table><caption>{}</caption></table>"),
          new Context("td", "<table><tbody><tr><td>{}</td></tr></tbody></table>"),
          new Context("td svg", "<table><tbody><tr><td><svg>{}</svg></td></tr></tbody></table>"),
          new Context("td svg>foreignObject",
              "<table><tbody><tr><td><svg><foreignObject>{}</foreignObject></svg></td></tr></tbody></table>"),
          new Context("td math>mi", "<table><tbody><tr><td><math><mi>{}</mi></math></td></tr></tbody></table>"),
          new Context("caption svg>desc", "<table><caption><svg><desc>{}</desc></svg></caption></table>"),
          new Context("tr after td>svg reset (#398)",
              "<table><tbody><tr><td>" + RESET_SVG + "</td>{}</tr></tbody></table>"),
          new Context("tr after td>math>annotation-xml reset (#398)",
              "<table><tbody><tr><td><math><annotation-xml encoding=\"text/html\"><div><table></table></div>"
                  + "</annotation-xml></math></td>{}</tr></tbody></table>"),
          new Context("tr after td>math>mtext reset (#398)",
              "<table><tbody><tr><td><math><mtext><table></table></mtext></math></td>{}</tr></tbody></table>"),
          new Context("table after caption>svg reset (#398)", "<table><caption>" + RESET_SVG + "</caption>{}</table>"),
          new Context("td after svg reset (#398)", "<table><tbody><tr><td>" + RESET_SVG + "{}</td></tr></tbody></table>"),
          new Context("table>template", "<table><template>{}</template></table>"),
          new Context("math>annotation-xml(text/html)", "<math><annotation-xml encoding=\"text/html\">{}</annotation-xml></math>"),
          new Context("td select", "<table><tbody><tr><td><select>{}</select></td></tr></tbody></table>"),
          new Context("template>tr after td", "<template><tr><td>a</td>{}</tr></template>"));

  public static final List<Probe> PROBES =
      List.of(
          new Probe("[[s]]", "[[s]]"),
          new Probe("span t:text", "<span t:text=\"s\"></span>"),
          new Probe("input t:type", "<input t:type=\"s\">"),
          new Probe("td t:title", "<td t:title=\"s\">[[s]]</td>"),
          new Probe("title='[[s]]'", "<span title='[[s]]'>x</span>"),
          new Probe("title=[[s]]", "<span title=[[s]]>x</span>"),
          new Probe("font t:color", "<font t:color=\"s\">x</font>"),
          new Probe("font t:face", "<font t:face=\"s\">x</font>"),
          new Probe("font t:size", "<font t:size=\"s\">x</font>"),
          new Probe("annotation-xml t:encoding", "<math><annotation-xml t:encoding=\"s\"><mi>x</mi></annotation-xml></math>"),
          new Probe("b t:class", "<b t:class=\"s\">x</b>"),
          new Probe("[(h)]", "[(h)]"),
          new Probe("button t:type", "<button t:type=\"s\">x</button>"));

  /** Every context with every probe, each id once. */
  public static List<GeneratedCase> cases() {
    Map<String, GeneratedCase> out = new LinkedHashMap<>();
    for (Context c : CONTEXTS) {
      for (Probe p : PROBES) {
        List<Param> params = new ArrayList<>();
        params.add(Shape.STRING);
        if (p.safeHtml()) {
          params.add(Shape.SAFE_HTML);
        }
        String template = "<div t:params=\"" + String.join(", ", params.stream().map(Param::toString).toList()) + "\">"
            + c.body().replace("{}", p.markup()) + "</div>";
        OracleCase oc = OracleCase.of("deciding " + c.label() + " | " + p.label(), template, OracleCase.Origin.GENERATED);
        out.putIfAbsent(oc.id(), new GeneratedCase(oc, params, null));
      }
    }
    return List.copyOf(out.values());
  }
}
