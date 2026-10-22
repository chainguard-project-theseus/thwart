package dev.tessera.compiler.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.Diagnostic;
import dev.tessera.compiler.Diagnostics;
import dev.tessera.compiler.SourcePos;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpressionParserTest {

  private static final SourcePos START = new SourcePos("t.html", 3, 10);
  private final ExpressionParser parser = new ExpressionParser();

  // ---- helpers ----

  private Expr expr(String text) {
    Diagnostics d = new Diagnostics();
    Expr e = parser.parseExpression(text, START, d);
    assertEquals(List.of(), d.all(), "diagnostics for " + text);
    return e;
  }

  private String ast(String text) {
    return show(expr(text));
  }

  private Diagnostic error(String text) {
    return error(text, (t, d) -> parser.parseExpression(t, START, d));
  }

  private interface Parse {
    Object apply(String text, Diagnostics d);
  }

  private static Diagnostic error(String text, Parse parse) {
    Diagnostics d = new Diagnostics();
    assertNull(parse.apply(text, d), "expected failure for " + text);
    assertEquals(1, d.all().size(), "diagnostics for " + text + ": " + d.all());
    assertTrue(d.hasErrors());
    return d.all().get(0);
  }

  private static String show(Expr e) {
    return switch (e) {
      case null -> "~";
      case Expr.Literal l when l.value() instanceof String s -> "'" + s + "'";
      case Expr.Literal l when l.value() instanceof Long n -> n + "L";
      case Expr.Literal l when l.value() instanceof Double n -> n + "D";
      case Expr.Literal l -> String.valueOf(l.value());
      case Expr.Var v -> v.name();
      case Expr.SelectionVar v -> "*" + v.name();
      case Expr.Property p -> "(" + (p.nullSafe() ? "?." : ".") + " " + show(p.target()) + " " + p.name() + ")";
      case Expr.MethodCall m ->
          "(" + (m.nullSafe() ? "?call" : "call") + " " + show(m.target()) + " " + m.name() + all(m.args()) + ")";
      case Expr.Index x -> "([] " + show(x.target()) + " " + show(x.index()) + ")";
      case Expr.FunctionCall f -> "(#" + f.namespace() + "." + f.name() + all(f.args()) + ")";
      case Expr.Unary u -> "(" + u.op() + " " + show(u.operand()) + ")";
      case Expr.Binary b -> "(" + b.op() + " " + show(b.left()) + " " + show(b.right()) + ")";
      case Expr.Conditional c -> "(? " + show(c.condition()) + " " + show(c.then()) + " " + show(c.otherwise()) + ")";
      case Expr.Elvis v -> "(?: " + show(v.value()) + " " + show(v.fallback()) + ")";
      case Expr.Message m -> "(msg " + m.key() + all(m.args()) + ")";
      case Expr.Link l -> "(link " + l.path()
          + l.params().stream().map(p -> " " + p.name() + "=" + show(p.value())).collect(Collectors.joining()) + ")";
      case Expr.Interpolation i -> "(interp" + all(i.parts()) + ")";
      case Expr.FragmentExpr f -> "(frag " + show(f.ref()) + ")";
    };
  }

  private static String show(FragmentRef r) {
    return r.template() + "::" + r.selector()
        + r.args().stream().map(a -> " " + (a.name() == null ? "" : a.name() + "=") + show(a.value()))
            .collect(Collectors.joining());
  }

  private static String all(List<Expr> args) {
    return args.stream().map(a -> " " + show(a)).collect(Collectors.joining());
  }

  // ---- literals and primaries ----

  @Test
  void literals() {
    assertEquals("'abc'", ast("'abc'"));
    assertEquals("'it's'", ast("'it\\'s'"));
    assertEquals("'a\\b'", ast("'a\\\\b'"));
    assertEquals("''", ast("''"));
    assertEquals("42", ast("42"));
    assertEquals(42, ((Expr.Literal) expr("42")).value());
    assertEquals("42L", ast("42L"));
    assertEquals("1.5D", ast("1.5"));
    assertEquals("true", ast("true"));
    assertEquals("false", ast("false"));
    assertNull(((Expr.Literal) expr("null")).value());
  }

  @Test
  void variablesPropertiesAndCalls() {
    assertEquals("owner", ast("owner"));
    assertEquals("(. (. owner pets) size)", ast("owner.pets.size"));
    assertEquals("(call owner getPets)", ast("owner.getPets()"));
    assertEquals("(call strings abbreviate (. o name) 10)", ast("strings.abbreviate(o.name, 10)"));
    assertEquals("(?. (?. a b) c)", ast("a?.b?.c"));
    assertEquals("(?call a m 1)", ast("a?.m(1)"));
    assertEquals("([] items 0)", ast("items[0]"));
    assertEquals("(. ([] map 'k') name)", ast("map['k'].name"));
    assertEquals("([] ([] a i) (ADD j 1))", ast("a[i][j + 1]"));
    assertEquals("(#temporals.format (. v date) 'yyyy-MM-dd')", ast("#temporals.format(v.date, 'yyyy-MM-dd')"));
    assertEquals("(#lists.isEmpty)", ast("#lists.isEmpty()"));
  }

  @Test
  void wrappersAreAcceptedAnywhere() {
    assertEquals("(. owner name)", ast("${owner.name}"));
    assertEquals("(. owner name)", ast("  ${ owner.name }  "));
    assertEquals("(ADD (. a x) (. b y))", ast("${a.x} + ${b.y}"));
    assertEquals("(. a b)", ast("${a}.b"));
    assertEquals("*lastName", ast("*{lastName}"));
    assertEquals("(. (. *owner address) city)", ast("*{owner.address.city}"));
    assertEquals("(?: *name 'x')", ast("*{name} ?: 'x'"));
  }

  // ---- operators ----

  @Test
  void precedence() {
    assertEquals("(ADD a (MUL b c))", ast("a + b * c"));
    assertEquals("(MUL (ADD a b) c)", ast("(a + b) * c"));
    assertEquals("(OR a (AND b c))", ast("a || b && c"));
    assertEquals("(AND (EQ a b) (NE c d))", ast("a == b && c != d"));
    assertEquals("(EQ (LT a b) true)", ast("a < b == true"));
    assertEquals("(GT (ADD a 1) (MOD b 2))", ast("a + 1 > b % 2"));
    assertEquals("(ADD (NEGATE a) b)", ast("-a + b"));
    assertEquals("(AND (NOT a) b)", ast("!a && b"));
    assertEquals("(? (OR a b) c d)", ast("a or b ? c : d"));
    assertEquals("(? (?: a b) c ~)", ast("a ?: b ? c"));
    assertEquals("(?: (OR a b) c)", ast("a || b ?: c"));
  }

  @Test
  void associativity() {
    assertEquals("(SUB (SUB a b) c)", ast("a - b - c"));
    assertEquals("(DIV (MUL a b) c)", ast("a * b / c"));
    assertEquals("(OR (OR a b) c)", ast("a or b or c"));
    assertEquals("(?: a (?: b c))", ast("a ?: b ?: c"));
    assertEquals("(? a b (? c d e))", ast("a ? b : c ? d : e"));
    assertEquals("(? a (? b c d) e)", ast("a ? b ? c : d : e"));
    assertEquals("(NOT (NOT a))", ast("!!a"));
    assertEquals("(SUB a (NEGATE 1))", ast("a - -1"));
  }

  @Test
  void ternaryWithoutElse() {
    assertEquals("(? (. o active) 'yes' ~)", ast("o.active ? 'yes'"));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '"', commentCharacter = '%', value = {
    "a and b|(AND a b)",
    "a or b|(OR a b)",
    "not a|(NOT a)",
    "a eq b|(EQ a b)",
    "a ne b|(NE a b)",
    "a lt b|(LT a b)",
    "a gt b|(GT a b)",
    "a le b|(LE a b)",
    "a ge b|(GE a b)",
    "a <= b|(LE a b)",
    "a >= b|(GE a b)",
    "a > b|(GT a b)",
    "a % b|(MOD a b)",
    "order.andThen|(. order andThen)",
    "orange|orange",
    "notes|notes",
  })
  void textualAliases(String text, String expected) {
    assertEquals(expected, ast(text));
  }

  // ---- messages, links, interpolation ----

  @Test
  void messages() {
    assertEquals("(msg welcome)", ast("#{welcome}"));
    assertEquals("(msg owner.find-by_name)", ast("#{ owner.find-by_name }"));
    assertEquals("(msg greeting (. u name) 3)", ast("#{greeting(u.name, 3)}"));
    assertEquals("(msg empty)", ast("#{empty()}"));
  }

  @Test
  void links() {
    assertEquals("(link /owners)", ast("@{/owners}"));
    assertEquals("(link /owners/{ownerId}/edit ownerId=(. owner id))", ast("@{/owners/{ownerId}/edit(ownerId=${owner.id})}"));
    assertEquals("(link /owners lastName=q page=(ADD p 1))", ast("@{/owners(lastName=q, page=p + 1)}"));
    assertEquals("(link https://example.com/a#top)", ast("@{https://example.com/a#top}"));
    assertEquals("(link ../x.css)", ast("@{ ../x.css }"));
  }

  @Test
  void interpolation() {
    assertEquals("(interp 'Hello ' (. u name) '!')", ast("|Hello ${u.name}!|"));
    assertEquals("(interp *a '-' (msg k))", ast("|*{a}-#{k}|"));
    assertEquals("(interp)", ast("||"));
    assertEquals("(ADD (interp 'a ' x) 'b')", ast("|a ${x}| + 'b'"));
    assertEquals("(interp '{' x '}')", ast("|{${x}}|"));
  }

  // ---- fragments ----

  @Test
  void fragmentExpressions() {
    assertEquals("(frag a/b::f x y)", ast("~{a/b :: f(x, y)}"));
    assertEquals("(frag null::f)", ast("~{:: f}"));
    assertEquals("(frag null::f)", ast("~{this :: f}"));
    assertEquals("(frag null::body)", ast("~{::body}"));
    assertEquals("(frag fragments/layout-main.v2::x n=1)", ast("~{fragments/layout-main.v2 :: x(n=1)}"));
  }

  @Test
  void fragmentRefs() {
    assertEquals("a/b::f x y", show(ref("~{a/b :: f(x, y)}")));
    assertEquals("a/b::f n=x", show(ref("a/b :: f(n=x)")));
    assertEquals("null::f", show(ref("~{:: f}")));
    assertEquals("null::f", show(ref("~{this :: f}")));
    assertEquals("null::body", show(ref("~{::body}")));
    assertEquals("null::f", show(ref(":: f")));
    assertEquals("fragments/layout::layout (frag null::body) 'T'", show(ref("fragments/layout :: layout(~{::body}, 'T')")));
    assertEquals("x::f a=(EQ b c)", show(ref("x :: f(a = b == c)")));
    assertEquals(List.of(), ref("x :: f()").args());
  }

  private FragmentRef ref(String text) {
    Diagnostics d = new Diagnostics();
    FragmentRef r = parser.parseFragmentRef(text, START, d);
    assertEquals(List.of(), d.all(), "diagnostics for " + text);
    return r;
  }

  @Test
  void fragmentRefErrors() {
    Parse p = (t, d) -> parser.parseFragmentRef(t, START, d);
    assertEquals("fragment arguments must be all named or all positional", error("a :: f(x, n=y)", p).message());
    assertTrue(error("template", p).message().startsWith("expected '::'"));
    assertTrue(error("~{a :: #id}", p).message().contains("markup selectors are not supported"));
    assertTrue(error("~{${t} :: f}", p).message().startsWith("dynamic template names are not supported"));
    assertTrue(error("~{a b :: f}", p).message().startsWith("invalid character ' '"));
    assertTrue(error("~{a :: f", p).message().startsWith("expected '}' to close '~{'"));
  }

  // ---- positions ----

  @Test
  void positions() {
    Expr.Binary b = (Expr.Binary) expr("ab +\n  c.d");
    assertEquals(new SourcePos("t.html", 3, 10), b.pos());
    Expr.Property p = (Expr.Property) b.right();
    assertEquals(new SourcePos("t.html", 4, 3), p.pos());
    Expr.Unary u = (Expr.Unary) ((Expr.Binary) expr("x and !y")).right();
    assertEquals(new SourcePos("t.html", 3, 16), u.pos());
    Expr.Link link = (Expr.Link) expr(" @{/a(id=x)}");
    assertEquals(new SourcePos("t.html", 3, 11), link.pos());
    assertEquals(new SourcePos("t.html", 3, 16), link.params().get(0).pos());
    Expr.Interpolation i = (Expr.Interpolation) expr("|ab${c}|");
    assertEquals(new SourcePos("t.html", 3, 11), i.parts().get(0).pos());
    assertEquals(new SourcePos("t.html", 3, 15), i.parts().get(1).pos());
    assertEquals(new SourcePos("t.html", 3, 12), expr("*{a}").pos());
    assertEquals(new SourcePos("t.html", 3, 10), expr("#ns.f()").pos());
  }

  // ---- declarations ----

  @Test
  void each() {
    Diagnostics d = new Diagnostics();
    EachSpec e = parser.parseEach("o : ${owners}", START, d);
    assertEquals("o", e.var());
    assertNull(e.statVar());
    assertEquals("owners", show(e.iterable()));
    e = parser.parseEach(" pet, stat : owner.pets", START, d);
    assertEquals("pet", e.var());
    assertEquals("stat", e.statVar());
    assertEquals("(. owner pets)", show(e.iterable()));
    assertEquals(new SourcePos("t.html", 3, 11), e.pos());
    assertEquals(List.of(), d.all());

    Parse p = (t, dd) -> parser.parseEach(t, START, dd);
    assertTrue(error("o in owners", p).message().startsWith("expected ':'"));
    assertTrue(error("o, : owners", p).message().startsWith("expected a status variable name"));
    assertTrue(error(": owners", p).message().startsWith("expected a loop variable name"));
    assertTrue(error("o :", p).message().startsWith("expected an expression"));
  }

  @Test
  void assignments() {
    Diagnostics d = new Diagnostics();
    List<Assignment> as = parser.parseAssignments("data-id=${o.id}, xlink:href = @{/a}, aria.x=1 + 2", START, d);
    assertEquals(List.of(), d.all());
    assertEquals(List.of("data-id", "xlink:href", "aria.x"), as.stream().map(Assignment::name).toList());
    assertEquals("(. o id)", show(as.get(0).value()));
    assertEquals("(link /a)", show(as.get(1).value()));
    assertEquals("(ADD 1 2)", show(as.get(2).value()));
    assertEquals(new SourcePos("t.html", 3, 27), as.get(1).pos());

    Parse p = (t, dd) -> parser.parseAssignments(t, START, dd);
    assertTrue(error("a", p).message().startsWith("expected '=' after 'a'"));
    assertTrue(error("=1", p).message().startsWith("expected an attribute or variable name"));
    assertTrue(error("a=1,", p).message().startsWith("expected an attribute or variable name"));
    assertTrue(error("a=1 b=2", p).message().startsWith("unexpected 'b'"));
  }

  @Test
  void params() {
    Diagnostics d = new Diagnostics();
    assertEquals(List.of(), parser.parseParams("  ", START, d));
    List<ParamDecl> ps = parser.parseParams(
        "OwnerView owner, List<PetView> pets, Map<String, List<com.x.Item>> m, int[][] grid, String[] names", START, d);
    assertEquals(List.of(), d.all());
    assertEquals(List.of("owner", "pets", "m", "grid", "names"), ps.stream().map(ParamDecl::name).toList());
    assertEquals(new TypeRef("OwnerView", List.of(), 0, START), ps.get(0).type());
    TypeRef map = ps.get(2).type();
    assertEquals("Map", map.name());
    assertEquals("String", map.typeArgs().get(0).name());
    assertEquals("com.x.Item", map.typeArgs().get(1).typeArgs().get(0).name());
    assertEquals(2, ps.get(3).type().arrayDims());
    assertEquals("int", ps.get(3).type().name());
    assertEquals(1, ps.get(4).type().arrayDims());
    assertEquals(new SourcePos("t.html", 3, 27), ps.get(1).pos());

    Parse p = (t, dd) -> parser.parseParams(t, START, dd);
    assertTrue(error("owner", p).message().startsWith("expected a parameter name after type 'owner'"));
    assertTrue(error("List<? extends X> xs", p).message().startsWith("wildcard types are not supported"));
    assertTrue(error("List<X xs", p).message().startsWith("expected ',' or '>'"));
    assertTrue(error("A a,", p).message().startsWith("expected a type name"));
    assertTrue(error("int[ a", p).message().startsWith("expected ']'"));
  }

  @Test
  void imports() {
    Diagnostics d = new Diagnostics();
    assertEquals(List.of(), parser.parseImports("", START, d));
    assertEquals(List.of("com.example.owner.*", "com.example.vet.VetView", "Foo"),
        parser.parseImports(" com.example.owner.*,\n com.example.vet.VetView , Foo", START, d));
    assertEquals(List.of(), d.all());
    Parse p = (t, dd) -> parser.parseImports(t, START, dd);
    assertTrue(error("com.", p).message().startsWith("unexpected '.'"));
    assertTrue(error("com.x,", p).message().startsWith("expected a package or class name"));
    assertTrue(error("com x", p).message().startsWith("unexpected 'x'"));
  }

  @Test
  void fragmentSignatures() {
    Diagnostics d = new Diagnostics();
    FragmentSignature s = parser.parseFragmentSignature("card", START, d);
    assertEquals("card", s.name());
    assertEquals(List.of(), s.params());
    assertEquals(List.of(), parser.parseFragmentSignature("card()", START, d).params());
    s = parser.parseFragmentSignature(" card(OwnerView owner, Fragment body)", START, d);
    assertEquals(List.of(), d.all());
    assertEquals(new SourcePos("t.html", 3, 11), s.pos());
    assertEquals(List.of("owner", "body"), s.params().stream().map(ParamDecl::name).toList());
    assertEquals("Fragment", s.params().get(1).type().name());

    Parse p = (t, dd) -> parser.parseFragmentSignature(t, START, dd);
    assertTrue(error("card(a, b)", p).message().startsWith("expected a parameter name after type 'a'"));
    assertTrue(error("card(A a", p).message().startsWith("expected ',' or ')'"));
    assertTrue(error("(A a)", p).message().startsWith("expected a fragment name"));
  }

  // ---- rejected constructs (security) ----

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '"', commentCharacter = '%', value = {
    "T(java.lang.Runtime).getRuntime()|type references (T(...)) are not supported|1",
    "T (java.lang.Runtime)|type references (T(...)) are not supported|1",
    "new java.io.File('x')|object creation (new) is not supported|1",
    "__${x}__|preprocessing (__...__) is not supported: expressions are never built from strings|1",
    "a + '__x'|preprocessing (__...__) is not supported: expressions are never built from strings|6",
    "@bean.run()|bean references (@name) are not supported; pass values in as template parameters|1",
    "x + @bean|bean references (@name) are not supported; pass values in as template parameters|5",
    "a = b|assignment (=) is not supported; use == to compare|3",
    "x -> x|lambdas (->) are not supported|3",
    "->x|lambdas (->) are not supported|1",
    "list.![name]|collection projection (.![...]) is not supported|5",
    "list.?[a > 1]|collection selection (.?[...]) is not supported|5",
    "list.^[a]|collection selection (.?[...]) is not supported|5",
    "list.$[a]|collection selection (.?[...]) is not supported|5",
    "#{${key}}|dynamic message keys are not supported; the key must be a literal such as #{owner.name}|3",
    "#{'k' + x}|dynamic message keys are not supported; the key must be a literal such as #{owner.name}|3",
    "@{${url}}|dynamic link paths are not supported; the path must be a literal such as @{/owners/{id}(id=owner.id)}|3",
    "@{'/a/' + x}|dynamic link paths are not supported; the path must be a literal such as @{/owners/{id}(id=owner.id)}|3",
    "@{/a/*{x}}|dynamic link paths are not supported; the path must be a literal such as @{/owners/{id}(id=owner.id)}|3",
    "exec('ls')|'exec(...)' is not a known call; call functions as 'ns.exec(...)' or '#ns.exec(...)'|1",
  })
  void rejectedConstructs(String text, String message, int column) {
    Diagnostic d = error(text);
    assertEquals(message, d.message());
    assertEquals(new SourcePos("t.html", 3, 9 + column), d.pos());
  }

  @Test
  void syntaxErrorsNameWhatWasExpected() {
    assertEquals("expected an expression but reached the end", error("").message());
    assertEquals("expected an expression but reached the end", error("a +").message());
    assertEquals("expected ')' but reached the end", error("(a").message());
    assertEquals("expected '}' to close '${' but reached the end", error("${a").message());
    assertEquals("expected ']' to close the index but found ')'", error("a[1)").message());
    assertEquals("unterminated string literal", error("'abc").message());
    assertEquals("unexpected ')'", error("a)").message());
    assertEquals("unexpected 'b'", error("a b").message());
    assertEquals("expected a property or method name after '.' but found '1'", error("a.1").message());
    assertEquals("expected an expression but found operator 'and'", error("and b").message());
    assertEquals("expected ',' or ')' in the argument list but reached the end", error("f.g(1").message());
    assertEquals("expected '(' after '#ns.fn' but reached the end", error("#ns.fn").message());
    assertEquals("expected '.' after '#ns' but found '('", error("#ns(1)").message());
    assertEquals("unterminated literal substitution: expected a closing '|'", error("|abc").message());
    assertEquals("number literal out of range: 99999999999", error("99999999999").message());
    assertEquals("invalid number literal", error("12ab").message());
    assertEquals("invalid path variable '{1x}'", error("@{/a/{1x}}").message());
    assertEquals("link path must not contain whitespace", error("@{/a b}").message());
    assertEquals("expected '=' after link parameter 'x' but found ')'", error("@{/a(x)}").message());
    assertEquals("invalid character ' ' in message key 'a b'", error("#{a b}").message());
    assertEquals("expected a message key after '#{' but found '}'", error("#{}").message());
    assertEquals("expected a property name after '*{' but found '}'", error("*{}").message());
    assertEquals("expected an expression but found '$'", error("$x").message());
  }

  @Test
  void deepNestingIsAnErrorNotACrash() {
    String deep = "(".repeat(5000) + "a" + ")".repeat(5000);
    assertEquals("expression is nested too deeply", error(deep).message());
    assertEquals("expression is nested too deeply", error("!".repeat(5000) + "a").message());
    assertEquals("expression is nested too deeply", error("-".repeat(5000) + "a").message());
  }

  @Test
  void nullTextIsTreatedAsEmpty() {
    Diagnostics d = new Diagnostics();
    assertEquals(List.of(), parser.parseParams(null, START, d));
    assertNull(parser.parseExpression(null, START, d));
    assertTrue(d.hasErrors());
  }
}
