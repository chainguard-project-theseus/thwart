package dev.tessera.compiler.types;

import static dev.tessera.compiler.expr.Expr.BinaryOp.ADD;
import static dev.tessera.compiler.expr.Expr.BinaryOp.AND;
import static dev.tessera.compiler.expr.Expr.BinaryOp.DIV;
import static dev.tessera.compiler.expr.Expr.BinaryOp.EQ;
import static dev.tessera.compiler.expr.Expr.BinaryOp.GE;
import static dev.tessera.compiler.expr.Expr.BinaryOp.GT;
import static dev.tessera.compiler.expr.Expr.BinaryOp.LT;
import static dev.tessera.compiler.expr.Expr.BinaryOp.MOD;
import static dev.tessera.compiler.expr.Expr.BinaryOp.MUL;
import static dev.tessera.compiler.expr.Expr.BinaryOp.NE;
import static dev.tessera.compiler.expr.Expr.BinaryOp.OR;
import static dev.tessera.compiler.expr.Expr.BinaryOp.SUB;
import static dev.tessera.compiler.types.TypesHarness.at;
import static dev.tessera.compiler.types.TypesHarness.bin;
import static dev.tessera.compiler.types.TypesHarness.call;
import static dev.tessera.compiler.types.TypesHarness.cond;
import static dev.tessera.compiler.types.TypesHarness.elvis;
import static dev.tessera.compiler.types.TypesHarness.idx;
import static dev.tessera.compiler.types.TypesHarness.lit;
import static dev.tessera.compiler.types.TypesHarness.neg;
import static dev.tessera.compiler.types.TypesHarness.not;
import static dev.tessera.compiler.types.TypesHarness.prop;
import static dev.tessera.compiler.types.TypesHarness.safe;
import static dev.tessera.compiler.types.TypesHarness.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.types.TypesHarness.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpressionCompilerTest {
  private static final String ITEMS =
      "java.util.List.of(new com.example.Item(\"pen\", 2, new java.math.BigDecimal(\"10.00\")),"
          + " new com.example.Item(\"ink\", 5, java.math.BigDecimal.ONE))";
  private static final String ANN =
      "new com.example.User(\"Ann\", 30, true, com.example.Status.ACTIVE, null, "
          + ITEMS
          + ", java.util.Map.of(\"a\", java.util.List.of(new com.example.Item(\"x\", 1, null))),"
          + " new String[] {\"t0\", \"t1\"}, java.util.Optional.of(\"annie\"), null, 7, java.util.Map.of(1, \"one\"))";
  private static final String BOB =
      "new com.example.User(\"Bob\", 12, false, com.example.Status.SUSPENDED, "
          + ANN
          + ", java.util.List.of(), java.util.Map.of(), new String[0], java.util.Optional.empty(), true, null,"
          + " java.util.Map.of())";

  private static TypesHarness users() {
    return TypesHarness.create()
        .local("u", "com.example.User", ANN)
        .local("b", "com.example.User", BOB)
        .local("a", "com.example.Account", "new com.example.Account()")
        .local("p", "com.example.Pojo", "new com.example.Pojo()");
  }

  private static void assertError(Result r, String... fragments) {
    assertNull(r.code(), "expected an error, got code " + r.code());
    boolean found = r.errors().stream().anyMatch(e -> List.of(fragments).stream().allMatch(e::contains));
    assertTrue(found, "no error containing " + List.of(fragments) + " in " + r.errors());
  }

  // ------------------------------------------------------------- properties

  @Test
  void recordComponentsInAllThreeForms() {
    TypesHarness h = users();
    assertEquals("Ann", h.eval(prop(v("u"), "name")));
    assertEquals("Ann", h.eval(call(v("u"), "name")));
    assertEquals("Ann", h.eval(call(v("u"), "getName")));
    assertEquals(30, h.eval(prop(v("u"), "age")));
    Result r = h.compile(prop(v("u"), "age"));
    assertEquals("int", r.type());
    assertEquals("u.age()", r.code());
  }

  @Test
  void gettersOnRecordsAndTemplateViews() {
    TypesHarness h = users();
    assertEquals("D:Ann", h.eval(prop(v("u"), "display")));
    assertEquals(true, h.eval(prop(v("u"), "adult")));
    assertEquals(false, h.eval(call(v("b"), "isAdult")));
    assertEquals("a1", h.eval(prop(v("a"), "id")));
    assertEquals("a1", h.eval(call(v("a"), "id")));
    assertEquals("a1", h.eval(call(v("a"), "getId")));
    assertEquals(true, h.eval(prop(v("a"), "active")));
    assertEquals("u", h.eval(prop(v("a"), "URL")));
  }

  @Test
  void arbitraryMethodsAreRejected() {
    TypesHarness h = users();
    assertError(h.compile(call(v("u"), "delete")), "'delete' is not a property of com.example.User");
    assertError(h.compile(call(v("a"), "secret")), "'secret' is not a property");
    assertError(h.compile(call(v("a"), "close")), "'close' is not a property");
    assertError(h.compile(prop(v("u"), "static")), "'static' is not a property");
    assertError(h.compile(call(v("u"), "getWithArg", lit(1))), "method call 'getWithArg(...)' is not allowed");
    assertError(h.compile(call(v("u"), "name", lit(1))), "is not allowed");
  }

  @Test
  void objectMethodsAreNeverCallable() {
    TypesHarness h = users();
    for (String m : List.of("getClass", "hashCode", "wait", "toString", "notify", "notifyAll", "equals", "clone", "finalize")) {
      assertError(h.compile(call(v("u"), m)), "'" + m + "' is a java.lang.Object method");
      assertError(h.compile(call(v("a"), m)), "'" + m + "' is a java.lang.Object method");
    }
    assertError(h.compile(call(v("u"), "wait", lit(1L))), "'wait' is a java.lang.Object method");
    assertError(h.compile(prop(v("u"), "class")), "java.lang.Object method");
    assertError(h.compile(prop(v("u"), "hashCode")), "java.lang.Object method");
    assertError(h.compile(call(prop(v("u"), "name"), "getClass")), "java.lang.Object method");
  }

  @Test
  void neverViewSafeResultsAreRejected() {
    TypesHarness h = users();
    assertError(h.compile(prop(v("a"), "thread")), "returns java.lang.Thread", "never view-safe");
    assertError(h.compile(prop(v("a"), "type")), "java.lang.Class is never view-safe");
    assertError(h.compile(call(v("a"), "getLoader")), "java.lang.ClassLoader is never view-safe");
    // getDeclaringClass() on an enum returns Class.
    assertError(h.compile(prop(prop(v("u"), "status"), "declaringClass")), "java.lang.Class is never view-safe");
  }

  @Test
  void nonViewSafePojoIsRejected() {
    TypesHarness h = users();
    assertError(h.compile(prop(v("p"), "name")), "com.example.Pojo is not view-safe");
    // A POJO reached through a view-safe getter is an inert value: nothing can be read from it.
    assertError(h.compile(prop(prop(v("a"), "pojo"), "name")), "com.example.Pojo is not view-safe");
  }

  @Test
  void unknownIdentifier() {
    Result r =
        users().compile(f -> f.compiler().compile(new Expr.Property(new Expr.Var("nope", at(3, 9)), "x", false, at(3, 9)), f.scope));
    assertError(r, "t.html:3:9", "unknown identifier 'nope'");
    assertError(users().compile(v("x$1")), "unknown identifier 'x$1'");
    assertError(users().compile(call(v("nope"), "fn")), "unknown identifier or function namespace 'nope'");
  }

  // ------------------------------------------------------------- built-ins, index, generics

  @Test
  void builtins() {
    TypesHarness h = users();
    assertEquals(2, h.eval(prop(prop(v("u"), "items"), "size")));
    assertEquals(2, h.eval(call(prop(v("u"), "items"), "size")));
    assertEquals(false, h.eval(call(prop(v("u"), "items"), "isEmpty")));
    assertEquals(true, h.eval(prop(prop(v("b"), "items"), "empty")));
    assertEquals(1, h.eval(prop(prop(v("u"), "groups"), "size")));
    assertEquals(3, h.eval(prop(prop(v("u"), "name"), "length")));
    assertEquals(3, h.eval(call(prop(v("u"), "name"), "length")));
    assertEquals(false, h.eval(prop(prop(v("u"), "name"), "empty")));
    assertEquals(2, h.eval(prop(prop(v("u"), "tags"), "length")));
    assertEquals("pen", h.eval(prop(prop(prop(v("u"), "items"), "first"), "name")));
    assertEquals("ink", h.eval(prop(prop(prop(v("u"), "items"), "last"), "name")));
    assertEquals("pen", h.eval(prop(call(prop(v("u"), "items"), "getFirst"), "name")));
    assertEquals(true, h.eval(prop(prop(v("u"), "nick"), "present")));
    assertError(h.compile(call(prop(v("u"), "items"), "add", lit(1))), "not allowed");
    assertError(h.compile(call(prop(v("u"), "items"), "clear")), "'clear' is not a property");
    assertError(h.compile(call(prop(v("u"), "items"), "stream")), "'stream' is not a property");
  }

  @Test
  void indexListArrayMap() {
    TypesHarness h = users();
    assertEquals("ink", h.eval(prop(idx(prop(v("u"), "items"), lit(1)), "name")));
    assertEquals("t1", h.eval(idx(prop(v("u"), "tags"), lit(1))));
    assertEquals("one", h.eval(idx(prop(v("u"), "byId"), lit(1))));
    Result r = h.compile(prop(idx(idx(prop(v("u"), "groups"), lit("a")), lit(0)), "name"));
    assertEquals("java.lang.String", r.type());
    assertEquals("x", h.evalCode(r));
    assertEquals("java.util.List<com.example.Item>", h.compile(idx(prop(v("u"), "groups"), lit("a"))).type());
    assertError(h.compile(idx(prop(v("u"), "items"), lit("a"))), "list index must be an int");
    assertError(h.compile(idx(prop(v("u"), "groups"), lit(1))), "map key must be a java.lang.String");
    assertError(h.compile(idx(prop(v("u"), "name"), lit(0))), "cannot index java.lang.String");
  }

  @Test
  void nullSafeNavigation() {
    TypesHarness h = users();
    assertNull(h.eval(safe(prop(v("u"), "manager"), "name")));
    assertEquals("Ann", h.eval(safe(prop(v("b"), "manager"), "name")));
    Result r = h.compile(safe(prop(v("u"), "manager"), "age"));
    assertEquals("java.lang.Integer", r.type());
    assertNull(h.evalCode(r));
    assertEquals(2, h.eval(safe(prop(v("u"), "items"), "size")));
    // Chained: u.manager?.manager?.name
    assertNull(h.eval(safe(safe(prop(v("b"), "manager"), "manager"), "name")));
    assertError(h.compile(safe(v("u"), "delete")), "'delete' is not a property");
  }

  @Test
  void elvisOperator() {
    TypesHarness h = users();
    assertEquals("none", h.eval(elvis(safe(prop(v("u"), "manager"), "name"), lit("none"))));
    assertEquals("Ann", h.eval(elvis(safe(prop(v("b"), "manager"), "name"), lit("none"))));
    Result r = h.compile(elvis(prop(v("b"), "score"), lit(0)));
    assertEquals("java.lang.Integer", r.type());
    assertEquals(0, h.evalCode(r));
    assertEquals(30, h.eval(elvis(prop(v("u"), "age"), lit(1))));
    assertError(h.compile(elvis(prop(v("u"), "name"), lit(1))), "incompatible types");
  }

  @Test
  void ternaryTyping() {
    TypesHarness h = users();
    assertEquals("yes", h.eval(cond(prop(v("u"), "admin"), lit("yes"), lit("no"))));
    assertEquals("no", h.eval(cond(prop(v("b"), "admin"), lit("yes"), lit("no"))));
    Result r = h.compile(cond(prop(v("u"), "admin"), lit(1), lit(2L)));
    assertEquals("long", r.type());
    assertEquals(1L, h.evalCode(r));
    r = h.compile(cond(prop(v("b"), "admin"), prop(v("u"), "age"), lit(null)));
    assertEquals("java.lang.Integer", r.type());
    assertNull(h.evalCode(r));
    r = h.compile(cond(prop(v("b"), "admin"), lit("x"), null));
    assertEquals("java.lang.String", r.type());
    assertNull(h.evalCode(r));
    // Condition truthiness on a String.
    assertEquals("t", h.eval(cond(prop(v("u"), "name"), lit("t"), lit("f"))));
    assertError(h.compile(cond(prop(v("u"), "admin"), lit("x"), lit(1))), "incompatible types java.lang.String and int");
  }

  // ------------------------------------------------------------- operators

  @Test
  void stringConcatenation() {
    TypesHarness h = users();
    assertEquals("Hi Ann!", h.eval(bin(ADD, bin(ADD, lit("Hi "), prop(v("u"), "name")), lit("!"))));
    assertEquals("n30", h.eval(bin(ADD, lit("n"), prop(v("u"), "age"))));
    assertEquals("31x", h.eval(bin(ADD, bin(ADD, prop(v("u"), "age"), lit(1)), lit("x"))));
    assertEquals("s:ACTIVE", h.eval(bin(ADD, lit("s:"), prop(v("u"), "status"))));
    assertEquals("q\"\\\né", h.eval(lit("q\"\\\né")));
    assertError(h.compile(bin(ADD, lit("x"), v("p"))), "cannot output", "com.example.Pojo");
  }

  @Test
  void interpolation() {
    TypesHarness h = users();
    Expr e = new Expr.Interpolation(List.of(lit("Hi "), prop(v("u"), "name"), lit(", "), prop(v("u"), "age")), TypesHarness.P);
    assertEquals("Hi Ann, 30", h.eval(e));
    assertError(h.compile(new Expr.Interpolation(List.of(lit("x"), v("p")), TypesHarness.P)), "not view-safe");
  }

  @Test
  void arithmetic() {
    TypesHarness h = users();
    assertEquals(59, h.eval(bin(SUB, bin(MUL, prop(v("u"), "age"), lit(2)), lit(1))));
    assertEquals(15.0, h.eval(bin(DIV, prop(v("u"), "age"), lit(2.0))));
    assertEquals(2, h.eval(bin(MOD, prop(v("u"), "age"), lit(7))));
    assertEquals(-30, h.eval(neg(prop(v("u"), "age"))));
    assertEquals(8, h.eval(bin(ADD, prop(v("u"), "score"), lit(1))));
    assertEquals(31L, h.eval(bin(ADD, prop(v("u"), "age"), lit(1L))));
    assertError(h.compile(bin(SUB, prop(v("u"), "name"), lit(1))), "needs numbers");
    assertError(h.compile(neg(prop(v("u"), "name"))), "unary - needs a number");
  }

  @Test
  void comparisons() {
    TypesHarness h = users();
    assertEquals(true, h.eval(bin(GE, prop(v("u"), "age"), lit(18))));
    assertEquals(false, h.eval(bin(LT, prop(v("u"), "age"), lit(2.5))));
    assertEquals(true, h.eval(bin(GT, prop(v("u"), "score"), lit(1))));
    assertEquals(true, h.eval(bin(LT, prop(v("u"), "name"), lit("B"))));
    assertError(h.compile(bin(LT, prop(v("u"), "name"), lit(1))), "cannot compare java.lang.String and int");
    assertError(h.compile(bin(LT, prop(v("u"), "name"), lit(null))), "cannot compare");
  }

  @Test
  void logic() {
    TypesHarness h = users();
    assertEquals(true, h.eval(bin(AND, prop(v("u"), "admin"), bin(GT, prop(v("u"), "age"), lit(1)))));
    assertEquals(true, h.eval(bin(OR, prop(v("b"), "admin"), prop(v("u"), "items"))));
    assertEquals(false, h.eval(not(prop(v("u"), "admin"))));
    assertEquals(true, h.eval(not(prop(v("u"), "verified"))));
  }

  @Test
  void equality() {
    TypesHarness h = users();
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "age"), lit(30))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "age"), lit(30L))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "score"), lit(7L))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "age"), lit(30.0))));
    assertEquals(false, h.eval(bin(EQ, prop(v("b"), "score"), lit(0))));
    assertEquals(true, h.eval(bin(EQ, prop(idx(prop(v("u"), "items"), lit(0)), "price"), lit(10))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "name"), lit("Ann"))));
    assertEquals(true, h.eval(bin(NE, prop(v("u"), "name"), lit("Bob"))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "manager"), lit(null))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "admin"), lit(true))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "status"), prop(v("u"), "status"))));
    assertError(h.compile(bin(EQ, prop(v("u"), "admin"), lit(1))), "cannot compare boolean with int");
  }

  @Test
  void enumEqualsStringLiteral() {
    TypesHarness h = users();
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "status"), lit("ACTIVE"))));
    assertEquals(true, h.eval(bin(EQ, lit("SUSPENDED"), prop(v("b"), "status"))));
    assertEquals(true, h.eval(bin(NE, prop(v("u"), "status"), lit("SUSPENDED"))));
    assertEquals(false, h.eval(bin(EQ, safe(prop(v("u"), "manager"), "status"), lit("ACTIVE"))));
    Result r =
        h.compile(
            f ->
                f.compiler()
                    .compile(new Expr.Binary(EQ, prop(v("u"), "status"), new Expr.Literal("DELETED", at(2, 14)), TypesHarness.P), f.scope));
    assertError(r, "t.html:2:14", "'DELETED' is not a constant of enum com.example.Status", "ACTIVE, SUSPENDED");
  }

  // ------------------------------------------------------------- selection, routing

  @Test
  void selectionVariables() {
    TypesHarness h = users();
    Result r =
        h.compile(
            f -> {
              Scope s = f.scope.withSelection(new Scope.Selection("user", "u", f.type("com.example.User")));
              return f.compiler().compile(prop(new Expr.SelectionVar("manager", TypesHarness.P), "name"), s);
            });
    assertEquals("u.manager().name()", r.code());
    assertError(h.compile(new Expr.SelectionVar("name", TypesHarness.P)), "*{name} can only be used inside t:object");
    assertError(
        h.compile(
            f ->
                f.compiler()
                    .compile(
                        new Expr.SelectionVar("delete", TypesHarness.P),
                        f.scope.withSelection(new Scope.Selection("user", "u", f.type("com.example.User"))))),
        "'delete' is not a property");
  }

  @Test
  void fieldsRoutesToFieldsCompiler() {
    // FieldsCompiler is a stub (#15) that reports "not implemented yet"; routing is what we check.
    assertError(users().compile(call(v("fields"), "hasErrors", lit("name"))), "fields.hasErrors");
    assertError(users().compile(TypesHarness.fn("fields", "errors", lit("name"))), "fields.errors");
    assertError(users().compile(v("fields")), "'fields' is a function namespace");
  }

  @Test
  void fragmentExpressionIsNotAValue() {
    Expr frag =
        new Expr.FragmentExpr(new dev.tessera.compiler.expr.FragmentRef(null, "card", List.of(), TypesHarness.P), TypesHarness.P);
    assertError(users().compile(frag), "fragment expression");
  }

  // ------------------------------------------------------------- conditions

  @Test
  void compileConditionTruthiness() {
    TypesHarness h = users();
    Result r = h.compile(f -> f.compiler().compileCondition(prop(v("u"), "admin"), f.scope));
    assertEquals("u.admin()", r.code());
    assertEquals("boolean", r.type());
    r = h.compile(f -> f.compiler().compileCondition(prop(v("u"), "verified"), f.scope));
    assertEquals("java.lang.Boolean.TRUE.equals(u.verified())", r.code());
    assertEquals(false, h.evalCode(r));
    r = h.compile(f -> f.compiler().compileCondition(prop(v("b"), "verified"), f.scope));
    assertEquals(true, h.evalCode(r));
    r = h.compile(f -> f.compiler().compileCondition(prop(v("u"), "items"), f.scope));
    assertTrue(r.code().startsWith("dev.tessera.runtime.Conditions.isTrue("));
    assertEquals(true, h.evalCode(r));
    r = h.compile(f -> f.compiler().compileCondition(prop(v("u"), "manager"), f.scope));
    assertEquals(false, h.evalCode(r));
    r = h.compile(f -> f.compiler().compileCondition(prop(v("u"), "age"), f.scope));
    assertEquals(true, h.evalCode(r));
    r = h.compile(f -> f.compiler().compileCondition(lit(null), f.scope));
    assertEquals("false", r.code());
  }

  // ------------------------------------------------------------- elementType

  @Test
  void elementTypes() {
    users()
        .run(
            f -> {
              ExpressionCompiler ec = f.compiler();
              assertEquals("com.example.Item", ec.elementType(ec.compile(prop(v("u"), "items"), f.scope)).toString());
              assertEquals("java.lang.String", ec.elementType(ec.compile(prop(v("u"), "tags"), f.scope)).toString());
              assertEquals(
                  "java.util.Map.Entry<java.lang.String,java.util.List<com.example.Item>>",
                  ec.elementType(ec.compile(prop(v("u"), "groups"), f.scope)).toString());
              f.var("s", "java.util.Set<com.example.Item>");
              f.var("it", "java.lang.Iterable<java.lang.String>");
              f.var("w", "java.util.ArrayList<java.lang.Integer>");
              f.var("raw", "java.util.List");
              assertEquals("com.example.Item", ec.elementType(ec.compile(v("s"), f.scope)).toString());
              assertEquals("java.lang.String", ec.elementType(ec.compile(v("it"), f.scope)).toString());
              assertEquals("java.lang.Integer", ec.elementType(ec.compile(v("w"), f.scope)).toString());
              assertTrue(f.errors().isEmpty(), f.errors().toString());
              assertNull(ec.elementType(ec.compile(prop(v("u"), "name"), f.scope)));
              assertNull(ec.elementType(ec.compile(v("raw"), f.scope)));
              assertNull(ec.elementType(null));
              assertEquals(2, f.errors().size(), f.errors().toString());
              assertTrue(f.errors().get(0).contains("cannot iterate over java.lang.String"));
              assertTrue(f.errors().get(1).contains("raw type"));
              // The deny-list applies to the element type even when the iterable itself passes it.
              assertNull(ec.elementType(ec.compile(prop(v("a"), "threads"), f.scope)));
              assertEquals(3, f.errors().size(), f.errors().toString());
              assertTrue(f.errors().get(2).contains("java.lang.Thread is never view-safe"), f.errors().get(2));
            });
  }

  // ------------------------------------------------------------- checkOutputType

  @Test
  void outputTypes() {
    users()
        .run(
            f -> {
              ExpressionCompiler ec = f.compiler();
              assertTrue(ec.checkOutputType(f.type("java.lang.String"), TypesHarness.P));
              assertTrue(ec.checkOutputType(f.type("int"), TypesHarness.P));
              assertTrue(ec.checkOutputType(f.type("com.example.Status"), TypesHarness.P));
              assertTrue(ec.checkOutputType(f.type("dev.tessera.runtime.SafeHtml"), TypesHarness.P));
              assertTrue(f.errors().isEmpty());
              assertFalse(ec.checkOutputType(f.type("com.example.Pojo"), at(4, 2)));
              assertFalse(ec.checkOutputType(f.type("java.lang.Object"), at(4, 2)));
              assertFalse(ec.checkOutputType(f.type("dev.tessera.runtime.Fragment"), at(4, 2)));
              assertFalse(ec.checkOutputType(f.type("java.lang.Thread"), at(4, 2)));
              assertEquals(4, f.errors().size());
              assertTrue(f.errors().get(0).startsWith("t.html:4:2: error: cannot output"));
            });
  }

  @Test
  void javaStringEscapesEverything() {
    assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\001\\u00e9\"", ExpressionCompiler.javaString("a\"b\\c\n\r\t\u0001é"));
    // A backslash-u sequence in a template string must not become a unicode escape in Java.
    assertEquals("\"\\\\u0022\"", ExpressionCompiler.javaString("\\u0022"));
    assertEquals("\\u0022)", users().eval(bin(ADD, lit("\\u0022"), lit(")"))));
  }
}
