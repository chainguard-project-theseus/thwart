package dev.chainguard.thwart.compiler.types;

import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.ADD;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.AND;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.DIV;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.EQ;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.GE;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.GT;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.LT;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.MOD;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.MUL;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.NE;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.OR;
import static dev.chainguard.thwart.compiler.expr.Expr.BinaryOp.SUB;
import static dev.chainguard.thwart.compiler.types.TypesHarness.at;
import static dev.chainguard.thwart.compiler.types.TypesHarness.bin;
import static dev.chainguard.thwart.compiler.types.TypesHarness.call;
import static dev.chainguard.thwart.compiler.types.TypesHarness.cond;
import static dev.chainguard.thwart.compiler.types.TypesHarness.elvis;
import static dev.chainguard.thwart.compiler.types.TypesHarness.idx;
import static dev.chainguard.thwart.compiler.types.TypesHarness.lit;
import static dev.chainguard.thwart.compiler.types.TypesHarness.neg;
import static dev.chainguard.thwart.compiler.types.TypesHarness.not;
import static dev.chainguard.thwart.compiler.types.TypesHarness.prop;
import static dev.chainguard.thwart.compiler.types.TypesHarness.safe;
import static dev.chainguard.thwart.compiler.types.TypesHarness.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.types.TypesHarness.Result;
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

  private static Result condition(TypesHarness h, Expr e) {
    return h.compile(f -> f.compiler().compileCondition(e, f.scope));
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
  void isPropertyReadsGetIsGetterAsSpelDoes() {
    // #130: Lombok generates getIsApproved() for a Boolean isApproved field; SpEL, OGNL and
    // JavaBeans resolve the property x.isApproved to it.
    TypesHarness h = users();
    assertEquals(true, h.eval(prop(v("a"), "isApproved")));
    assertEquals("a.getIsApproved()", h.compile(prop(v("a"), "isApproved")).code());
    assertEquals(true, h.eval(safe(v("a"), "isApproved")));
    assertEquals(true, h.eval(call(v("a"), "getIsApproved")));
    // The property prefers getIsPrimary() as SpEL does; the call isPrimary() is exactly that method.
    assertEquals("getIsPrimary", h.eval(prop(v("a"), "isPrimary")));
    assertEquals(true, h.eval(call(v("a"), "isPrimary")));
    assertEquals(true, h.eval(prop(v("a"), "primary")));
    // isX() still resolves as before when there is no getIsX().
    assertEquals(true, h.eval(prop(v("a"), "isActive")));
    // The call form does not fall back to getIsApproved(), and the deny-list still applies.
    h.compile(call(v("a"), "isApproved")).assertError("'isApproved' is not a property of com.example.Account");
    h.compile(prop(v("a"), "isRunning")).assertError("returns java.lang.Thread", "never view-safe");
  }

  @Test
  void javaUtilImplementationsReadAsTheirInterface() {
    // #135: an ArrayList, TreeMap, SortedMap, ArrayDeque or SimpleEntry is read as its List, Map,
    // Collection or Map.Entry: built-ins, index access and the interface's getters only.
    TypesHarness h = TypesHarness.create()
        .local("al", "java.util.ArrayList<com.example.Item>", "new java.util.ArrayList<>(" + ITEMS + ")")
        .local("tm", "java.util.TreeMap<java.lang.String, java.lang.Integer>", "new java.util.TreeMap<>(java.util.Map.of(\"b\", 2, \"a\", 1))")
        .local("sm", "java.util.SortedMap<java.lang.String, java.lang.Integer>", "new java.util.TreeMap<>(java.util.Map.of(\"a\", 1))")
        .local("dq", "java.util.ArrayDeque<java.lang.String>", "new java.util.ArrayDeque<>(java.util.List.of(\"x\", \"y\"))")
        .local("en", "java.util.AbstractMap.SimpleEntry<java.lang.String, java.lang.Integer>", "new java.util.AbstractMap.SimpleEntry<>(\"k\", 3)");
    assertEquals(2, h.eval(prop(v("al"), "size")));
    assertEquals(false, h.eval(prop(v("al"), "empty")));
    assertEquals("pen", h.eval(prop(prop(v("al"), "first"), "name")));
    assertEquals("ink", h.eval(prop(idx(v("al"), lit(1)), "name")));
    assertEquals(2, h.eval(prop(v("tm"), "size")));
    assertEquals(1, h.eval(idx(v("tm"), lit("a"))));
    assertEquals(1, h.eval(call(v("sm"), "size")));
    assertEquals(2, h.eval(prop(v("dq"), "size")));
    assertEquals("k", h.eval(prop(v("en"), "key")));
    assertEquals(3, h.eval(call(v("en"), "getValue")));
    // The classes' own methods stay unreachable, getter-shaped or not.
    h.compile(call(v("al"), "ensureCapacity", lit(10))).assertError("method call 'ensureCapacity(...)' is not allowed");
    h.compile(call(v("al"), "trimToSize")).assertError("'trimToSize' is not a property of java.util.ArrayList<com.example.Item>");
    h.compile(call(v("tm"), "pollFirstEntry")).assertError("'pollFirstEntry' is not a property");
    h.compile(call(v("tm"), "firstKey")).assertError("'firstKey' is not a property");
    h.compile(prop(v("sm"), "comparator")).assertError("'comparator' is not a property");
    h.compile(call(v("dq"), "getFirst")).assertError("'getFirst' is not a property of java.util.ArrayDeque<java.lang.String>");
    h.compile(prop(v("dq"), "first")).assertError("'first' is not a property");
    h.compile(call(v("en"), "setValue", lit(1))).assertError("is not allowed");
    h.compile(call(v("al"), "getClass")).assertError("java.lang.Object method");
  }

  @Test
  void arbitraryMethodsAreRejected() {
    TypesHarness h = users();
    h.compile(call(v("u"), "delete")).assertError("'delete' is not a property of com.example.User");
    h.compile(call(v("a"), "secret")).assertError("'secret' is not a property");
    h.compile(call(v("a"), "close")).assertError("'close' is not a property");
    h.compile(prop(v("u"), "static")).assertError("'static' is not a property");
    h.compile(call(v("u"), "getWithArg", lit(1))).assertError("method call 'getWithArg(...)' is not allowed");
    h.compile(call(v("u"), "name", lit(1))).assertError("is not allowed");
  }

  @Test
  void objectMethodsAreNeverCallable() {
    TypesHarness h = users();
    for (String m : List.of("getClass", "hashCode", "wait", "toString", "notify", "notifyAll", "equals", "clone", "finalize")) {
      h.compile(call(v("u"), m)).assertError("'" + m + "' is a java.lang.Object method");
      h.compile(call(v("a"), m)).assertError("'" + m + "' is a java.lang.Object method");
    }
    h.compile(call(v("u"), "wait", lit(1L))).assertError("'wait' is a java.lang.Object method");
    h.compile(prop(v("u"), "class")).assertError("java.lang.Object method");
    h.compile(prop(v("u"), "hashCode")).assertError("java.lang.Object method");
    h.compile(call(prop(v("u"), "name"), "getClass")).assertError("java.lang.Object method");
  }

  @Test
  void neverViewSafeResultsAreRejected() {
    TypesHarness h = users();
    h.compile(prop(v("a"), "thread")).assertError("returns java.lang.Thread", "never view-safe");
    h.compile(prop(v("a"), "type")).assertError("java.lang.Class is never view-safe");
    h.compile(call(v("a"), "getLoader")).assertError("java.lang.ClassLoader is never view-safe");
    // getDeclaringClass() on an enum returns Class.
    h.compile(prop(prop(v("u"), "status"), "declaringClass")).assertError("java.lang.Class is never view-safe");
  }

  @Test
  void nonViewSafePojoIsRejected() {
    TypesHarness h = users();
    h.compile(prop(v("p"), "name")).assertError("com.example.Pojo is not view-safe");
    // A POJO reached through a view-safe getter is an inert value: nothing can be read from it.
    h.compile(prop(prop(v("a"), "pojo"), "name")).assertError("com.example.Pojo is not view-safe");
  }

  @Test
  void unknownIdentifier() {
    Result r =
        users().compile(f -> f.compiler().compile(new Expr.Property(new Expr.Var("nope", at(3, 9)), "x", false, at(3, 9)), f.scope));
    r.assertError("t.html:3:9", "unknown identifier 'nope'");
    users().compile(v("x$1")).assertError("unknown identifier 'x$1'");
    users().compile(call(v("nope"), "fn")).assertError("unknown identifier or function namespace 'nope'");
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
    h.compile(call(prop(v("u"), "items"), "add", lit(1))).assertError("not allowed");
    h.compile(call(prop(v("u"), "items"), "clear")).assertError("'clear' is not a property");
    h.compile(call(prop(v("u"), "items"), "stream")).assertError("'stream' is not a property");
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
    h.compile(idx(prop(v("u"), "items"), lit("a"))).assertError("list index must be an int");
    h.compile(idx(prop(v("u"), "groups"), lit(1))).assertError("map key must be a java.lang.String");
    h.compile(idx(prop(v("u"), "name"), lit(0))).assertError("cannot index java.lang.String");
    // #806: m.key is not SpEL's map read here; the error says to index.
    h.compile(prop(prop(v("u"), "groups"), "a")).assertError("'a' is not a property", "m['a']");
    h.compile(call(prop(v("u"), "nick"), "get")).assertError("'get' is not a property", "unwrap the Optional");
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
    h.compile(safe(v("u"), "delete")).assertError("'delete' is not a property");
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
    h.compile(elvis(prop(v("u"), "name"), lit(1))).assertError("incompatible types");
  }

  /** #807: {@code x ?: d} on a variable evaluates {@code d} only when {@code x} is null. */
  @Test
  void elvisOnVariableIsLazy() {
    TypesHarness h = users().local("s", "java.lang.String", "\"a\"").local("n", "java.lang.String", "null");
    // u.manager is null, so evaluating u.manager.name would throw.
    Expr throwing = prop(prop(v("u"), "manager"), "name");
    assertEquals("a", h.eval(elvis(v("s"), throwing)));
    assertEquals("(s != null ? s : u.manager().name())", h.compile(elvis(v("s"), throwing)).code());
    assertEquals("Ann", h.eval(elvis(v("n"), prop(v("u"), "name"))));
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
    h.compile(cond(prop(v("u"), "admin"), lit("x"), lit(1))).assertError("incompatible types java.lang.String and int");
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
    h.compile(bin(ADD, lit("x"), v("p"))).assertError("cannot output", "com.example.Pojo");
  }

  @Test
  void interpolation() {
    TypesHarness h = users();
    Expr e = new Expr.Interpolation(List.of(lit("Hi "), prop(v("u"), "name"), lit(", "), prop(v("u"), "age")), TypesHarness.P);
    assertEquals("Hi Ann, 30", h.eval(e));
    h.compile(new Expr.Interpolation(List.of(lit("x"), v("p")), TypesHarness.P)).assertError("not view-safe");
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
    h.compile(bin(SUB, prop(v("u"), "name"), lit(1))).assertError("needs numbers");
    h.compile(neg(prop(v("u"), "name"))).assertError("unary - needs a number");
  }

  @Test
  void comparisons() {
    TypesHarness h = users();
    assertEquals(true, h.eval(bin(GE, prop(v("u"), "age"), lit(18))));
    assertEquals(false, h.eval(bin(LT, prop(v("u"), "age"), lit(2.5))));
    assertEquals(true, h.eval(bin(GT, prop(v("u"), "score"), lit(1))));
    assertEquals(true, h.eval(bin(LT, prop(v("u"), "name"), lit("B"))));
    h.compile(bin(LT, prop(v("u"), "name"), lit(1))).assertError("cannot compare java.lang.String and int");
    h.compile(bin(LT, prop(v("u"), "name"), lit(null))).assertError("cannot compare");
  }

  /** #806: a BigDecimal compares with any number; arithmetic on one stays an error with a fix-it. */
  @Test
  void bigDecimalComparesWithNumbers() {
    TypesHarness h = TypesHarness.create()
        .local("p", "java.math.BigDecimal", "new java.math.BigDecimal(\"10.50\")")
        .local("i", "java.lang.Integer", "11");
    assertEquals(true, h.eval(bin(GT, v("p"), lit(10))));
    assertEquals(false, h.eval(bin(GT, v("p"), lit(10.5))));
    assertEquals(true, h.eval(bin(GE, v("p"), lit(10.5))));
    assertEquals(true, h.eval(bin(LT, v("p"), v("i"))));
    assertEquals(true, h.eval(bin(GT, lit(100L), v("p"))));
    assertEquals(false, h.eval(bin(LT, v("p"), v("p"))));
    h.compile(bin(MUL, v("p"), lit(2))).assertError("needs numbers", "no BigDecimal arithmetic", "@TemplateFunction");
    h.compile(bin(LT, v("p"), lit("1"))).assertError("cannot compare java.math.BigDecimal and java.lang.String");
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
    h.compile(bin(EQ, prop(v("u"), "admin"), lit(1))).assertError("cannot compare boolean with int");
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
    r.assertError("t.html:2:14", "'DELETED' is not a constant of enum com.example.Status", "ACTIVE, SUSPENDED");
  }

  @Test
  void equalityOfTypesThatNeverShareAValueIsAnError() {
    // Objects.equals("ACTIVE", Status.ACTIVE) is false: a String variable never equals an enum.
    TypesHarness h = users();
    Result r =
        h.compile(f -> f.compiler().compile(new Expr.Binary(EQ, prop(v("u"), "name"), prop(v("u"), "status"), at(2, 5)), f.scope));
    r.assertError("t.html:2:5", "cannot compare java.lang.String with com.example.Status", "always false", "status == 'ACTIVE'");
    h.compile(bin(EQ, prop(v("u"), "status"), prop(v("u"), "name"))).assertError("cannot compare com.example.Status with java.lang.String");
    h.compile(bin(NE, prop(v("u"), "name"), prop(v("u"), "status"))).assertError("always true");
    h.compile(bin(EQ, prop(v("u"), "name"), prop(v("u"), "age"))).assertError("cannot compare java.lang.String with int");
    h.compile(bin(EQ, prop(v("u"), "score"), lit("7"))).assertError("cannot compare java.lang.Integer with java.lang.String");
    h.compile(bin(EQ, prop(v("u"), "tags"), lit("t0"))).assertError("cannot compare java.lang.String[] with java.lang.String");
    h.compile(bin(EQ, prop(v("u"), "manager"), prop(v("u"), "name"))).assertError("cannot compare com.example.User with");
    // Types that can share a value still compare.
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "name"), prop(v("u"), "name"))));
    assertEquals(true, h.eval(bin(EQ, prop(v("u"), "status"), prop(v("u"), "status"))));
    assertEquals(false, h.eval(bin(EQ, prop(v("u"), "status"), prop(v("b"), "status"))));
    assertEquals(true, h.eval(bin(EQ, prop(v("b"), "manager"), prop(v("b"), "manager"))));
    assertEquals(false, h.eval(bin(EQ, prop(v("b"), "verified"), prop(v("b"), "admin"))));
    assertEquals(false, h.eval(bin(EQ, prop(v("u"), "score"), prop(v("u"), "age"))));
    assertEquals(true, h.eval(bin(EQ, safe(prop(v("u"), "manager"), "status"), lit(null))));
    assertEquals(false, h.eval(bin(EQ, prop(v("u"), "items"), prop(v("u"), "groups"))));
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
    h.compile(new Expr.SelectionVar("name", TypesHarness.P)).assertError("*{name} can only be used inside t:object");
    h.compile(
            f ->
                f.compiler()
                    .compile(
                        new Expr.SelectionVar("delete", TypesHarness.P),
                        f.scope.withSelection(new Scope.Selection("user", "u", f.type("com.example.User")))))
        .assertError("'delete' is not a property");
  }

  @Test
  void fieldsRoutesToFieldsCompiler() {
    // Both fields.x(...) and #fields.x(...) route to FieldsCompiler, which rejects them here
    // (no t:object) naming the function; FieldsCompilerTest covers its behaviour.
    users().compile(call(v("fields"), "hasErrors", lit("name"))).assertError("fields.hasErrors");
    users().compile(TypesHarness.fn("fields", "errors", lit("name"))).assertError("fields.errors");
    users().compile(v("fields")).assertError("'fields' is a function namespace");
  }

  @Test
  void fragmentExpressionIsNotAValue() {
    Expr frag =
        new Expr.FragmentExpr(new dev.chainguard.thwart.compiler.expr.FragmentRef(null, "card", List.of(), TypesHarness.P), TypesHarness.P);
    users().compile(frag).assertError("fragment expression");
  }

  // ------------------------------------------------------------- conditions

  @Test
  void compileConditionTruthiness() {
    TypesHarness h = users();
    Result r = condition(h, prop(v("u"), "admin"));
    assertEquals("u.admin()", r.code());
    assertEquals("boolean", r.type());
    r = condition(h, prop(v("u"), "verified"));
    assertEquals("java.lang.Boolean.TRUE.equals(u.verified())", r.code());
    assertEquals(false, h.evalCode(r));
    r = condition(h, prop(v("b"), "verified"));
    assertEquals(true, h.evalCode(r));
    r = condition(h, prop(v("u"), "items"));
    assertTrue(r.code().startsWith("dev.chainguard.thwart.runtime.internal.Conditions.isTrue("));
    assertEquals(true, h.evalCode(r));
    r = condition(h, prop(v("u"), "manager"));
    assertEquals(false, h.evalCode(r));
    r = condition(h, prop(v("u"), "age"));
    assertEquals(true, h.evalCode(r));
    r = condition(h, lit(null));
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
              assertTrue(ec.checkOutputType(f.type("java.lang.String"), TypesHarness.P, true));
              assertTrue(ec.checkOutputType(f.type("int"), TypesHarness.P, true));
              assertTrue(ec.checkOutputType(f.type("com.example.Status"), TypesHarness.P, true));
              assertTrue(ec.checkOutputType(f.type("dev.chainguard.thwart.runtime.SafeHtml"), TypesHarness.P, true));
              // A record or @TemplateView class is a value only where it is not printed (JSON).
              assertTrue(ec.checkOutputType(f.type("com.example.User"), TypesHarness.P, false));
              assertTrue(ec.checkOutputType(f.type("com.example.Account"), TypesHarness.P, false));
              assertTrue(f.errors().isEmpty());
              assertFalse(ec.checkOutputType(f.type("com.example.Pojo"), at(4, 2), true));
              assertFalse(ec.checkOutputType(f.type("java.lang.Object"), at(4, 2), true));
              assertFalse(ec.checkOutputType(f.type("dev.chainguard.thwart.runtime.Fragment"), at(4, 2), true));
              assertFalse(ec.checkOutputType(f.type("java.lang.Thread"), at(4, 2), true));
              assertEquals(4, f.errors().size());
              assertTrue(f.errors().get(0).startsWith("t.html:4:2: error: cannot output"));
              // As text, their toString() would print every component: rejected.
              assertFalse(ec.checkOutputType(f.type("com.example.User"), at(4, 2), true));
              assertFalse(ec.checkOutputType(f.type("com.example.Account"), at(4, 2), true));
              assertEquals(6, f.errors().size());
              assertTrue(f.errors().get(4).contains("com.example.User as text"), f.errors().get(4));
            });
  }

  @Test
  void backslashUInStringLiteralSurvivesCompilation() {
    // A backslash-u sequence in a template string must not become a unicode escape in Java.
    assertEquals("\\u0022)", users().eval(bin(ADD, lit("\\u0022"), lit(")"))));
  }
}
