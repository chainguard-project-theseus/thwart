package dev.chainguard.thwart.compiler.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.Diagnostics;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.expr.ExpressionParser;
import dev.chainguard.thwart.compiler.expr.ParamDecl;
import dev.chainguard.thwart.compiler.types.TypesHarness.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/** End-to-end over the real {@link ExpressionParser}: template source text to evaluated Java. */
class ParsedExpressionTest {
  private static final String FMT =
      """
      package com.example;
      @dev.chainguard.thwart.runtime.TemplateFunctions("fmt")
      public final class Fmt {
        @dev.chainguard.thwart.runtime.TemplateFunction public static String upper(String s) { return s.toUpperCase(); }
      }
      """;

  private static Expr parse(String text) {
    Diagnostics d = new Diagnostics();
    Expr e = new ExpressionParser().parseExpression(text, TypesHarness.P, d);
    assertTrue(d.all().isEmpty(), d.all().toString());
    return e;
  }

  private static TypesHarness harness() {
    return TypesHarness.create()
        .java("com.example.Fmt", FMT)
        .functions("com.example.Fmt")
        .local(
            "u",
            "com.example.User",
            "new com.example.User(\"Ann\", 30, true, com.example.Status.ACTIVE, null,"
                + " java.util.List.of(new com.example.Item(\"pen\", 2, null)), java.util.Map.of(), new String[0],"
                + " java.util.Optional.empty(), null, null, java.util.Map.of())");
  }

  @Test
  void parsedExpressionsEvaluate() {
    TypesHarness h = harness();
    assertEquals("ANN", h.eval(parse("#fmt.upper(u.name)")));
    assertEquals("ANN", h.eval(parse("${fmt.upper(u.getName())}")));
    assertEquals("none", h.eval(parse("u.manager?.name ?: 'none'")));
    assertEquals(true, h.eval(parse("u.status == 'ACTIVE' and u.age gt 18")));
    assertEquals("Hi Ann (1)", h.eval(parse("|Hi ${u.name} (${u.items.size()})|")));
    assertEquals("pen", h.eval(parse("u.items[0].name")));
    assertEquals("adult", h.eval(parse("u.age >= 18 ? 'adult' : 'minor'")));
  }

  @Test
  void nullSafeResultIsNotComparedOrComputed() {
    TypesHarness h = harness().local("none", "java.util.List<com.example.User>", "java.util.List.of()");
    for (String bad :
        List.of("u.manager?.age > 18", "1 <= u.manager?.age", "u.manager?.age + 1", "-u.manager?.age", "u.manager?.name < 'b'")) {
      Result r = h.compile(parse(bad));
      assertNull(r.code(), bad);
      assertTrue(r.errors().get(0).contains("a ?. result may be null"), r.errors().toString());
    }
    assertEquals(false, h.eval(parse("(u.manager?.age ?: 0) > 18")));
    assertEquals(false, h.eval(parse("u.manager != null and u.manager.age > 18")));
    assertEquals(true, h.eval(parse("u.manager?.age == null")));
    assertEquals("x null", h.eval(parse("'x ' + u.manager?.name")));
    assertNull(h.eval(parse("none.first?.name")));
    assertNull(h.eval(parse("none.last")));
  }

  @Test
  void parsedAttacksFail() {
    TypesHarness h = harness();
    for (String attack :
        List.of("u.getClass()", "u.name.getClass()", "u.class", "u.delete()", "u.hashCode()", "u.status.declaringClass")) {
      Result r = h.compile(parse(attack));
      assertNull(r.code(), attack);
      assertTrue(r.errors().get(0).startsWith("t.html:1:1: error: "), r.errors().toString());
    }
  }

  @Test
  void parsedParamsResolveWithImports() {
    harness()
        .run(
            f -> {
              Diagnostics d = new Diagnostics();
              ExpressionParser parser = new ExpressionParser();
              List<String> imports = parser.parseImports("com.example.*", TypesHarness.P, d);
              List<ParamDecl> params =
                  parser.parseParams("User user, List<Item> items, Map<String, List<Item>> groups, int n", TypesHarness.P, d);
              assertTrue(d.all().isEmpty(), d.all().toString());
              List<String> types = params.stream().map(p -> String.valueOf(f.compiler().resolveType(p.type(), imports))).toList();
              assertEquals(
                  List.of(
                      "com.example.User",
                      "java.util.List<com.example.Item>",
                      "java.util.Map<java.lang.String,java.util.List<com.example.Item>>",
                      "int"),
                  types);
              assertTrue(f.errors().isEmpty(), f.errors().toString());
            });
  }
}
