package dev.tessera.compiler.oracle.gen;

import dev.tessera.compiler.oracle.OracleCase;
import dev.tessera.compiler.oracle.gen.Shape.Param;
import java.util.List;
import java.util.Objects;

/**
 * A case from the generator (#321): the {@link OracleCase} the gate (#324) runs, plus what the gate
 * needs to fill it in.
 *
 * @param oracleCase the case; its template is complete ({@code <div t:params="...">...</div>})
 * @param params the template's parameters: {@code String s} always, or {@code SafeHtml h} instead
 *     for raw-output holes, and {@code Fragment f} for the fragment-parameter composition; the gate
 *     renders each with the matching hostile form
 * @param shape the grammar point the case was written from; null for an explore-mode mutant
 */
public record GeneratedCase(OracleCase oracleCase, List<Param> params, Shape shape) {

  public GeneratedCase {
    Objects.requireNonNull(oracleCase, "oracleCase");
    params = List.copyOf(params);
  }

  /** The case for a grammar point. */
  static GeneratedCase of(Shape shape, OracleCase.Origin origin) {
    return new GeneratedCase(OracleCase.of(shape.name(), shape.template(), origin), shape.params(), shape);
  }

  /**
   * The case in the seed file format of #320, so an explore-mode counterexample can be pasted into
   * {@code src/test/resources/oracle/seeds/}. The expectation and fix lines are placeholders to fill
   * in. The body drops the default {@code <div t:params="String s">} root, which the seed loader
   * adds back; other roots are kept.
   */
  public String seedText() {
    String template = oracleCase.template();
    String root = "<div t:params=\"String s\">";
    String body =
        template.startsWith(root) && template.endsWith("</div>")
            ? template.substring(root.length(), template.length() - "</div>".length())
            : template;
    return "# name: gen-" + oracleCase.id() + "\n"
        + "# expect-0dda2f1: TODO\n"
        + "# fixed-by: TODO\n"
        + body + "\n";
  }
}
