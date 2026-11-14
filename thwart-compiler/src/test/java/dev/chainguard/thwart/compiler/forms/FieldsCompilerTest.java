package dev.chainguard.thwart.compiler.forms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.CompilerEnv;
import dev.chainguard.thwart.compiler.Diagnostics;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.expr.Expr;
import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.compiler.types.CompiledExpr;
import dev.chainguard.thwart.compiler.types.ExpressionCompiler;
import dev.chainguard.thwart.compiler.types.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.lang.model.type.TypeMirror;
import org.junit.jupiter.api.Test;

class FieldsCompilerTest {
  private static final SourcePos P = new SourcePos("t.html", 1, 1);

  private record Result(String code, String type, List<String> errors) {}

  /** Compiles each expression inside javac with {@code owner} selected (or no selection). */
  private static List<Result> compile(boolean selected, List<Expr> exprs) {
    List<Result> results = new ArrayList<>();
    Compilation.builder()
        .java("com.example.Address", "package com.example; public record Address(String city) {}")
        .java("com.example.Owner", "package com.example; public record Owner(String lastName, Address address) {}")
        .inProcessor(
            pe -> {
              for (Expr e : exprs) {
                Diagnostics d = new Diagnostics();
                CompilerEnv env = Compilation.env(pe, d);
                TypeMirror owner = pe.getElementUtils().getTypeElement("com.example.Owner").asType();
                Scope scope = selected ? Scope.empty().withSelection(new Scope.Selection("owner", "o", owner)) : Scope.empty();
                CompiledExpr c = new ExpressionCompiler(env).compile(e, scope);
                results.add(
                    new Result(
                        c == null ? null : c.code(),
                        c == null ? null : c.type().toString(),
                        d.all().stream().map(x -> x.message()).toList()));
              }
            })
        .compile();
    return results;
  }

  private static Expr fields(String fn, Expr... args) {
    return new Expr.MethodCall(new Expr.Var("fields", P), fn, List.of(args), false, P);
  }

  private static Expr lit(Object v) {
    return new Expr.Literal(v, P);
  }

  @Test
  void hasErrorsAndErrors() {
    List<Result> r =
        compile(
            true,
            List.of(
                fields("hasErrors", lit("lastName")),
                fields("hasErrors", lit("*")),
                fields("errors", lit("address.city")),
                new Expr.FunctionCall("fields", "hasErrors", List.of(lit("lastName")), P)));
    assertEquals(new Result("ctx.forms().hasErrors(\"owner\", \"lastName\")", "boolean", List.of()), r.get(0));
    assertEquals(new Result("ctx.forms().hasErrors(\"owner\", \"*\")", "boolean", List.of()), r.get(1));
    assertEquals(
        new Result("ctx.forms().errors(\"owner\", \"address.city\")", "java.util.List<java.lang.String>", List.of()),
        r.get(2));
    assertEquals(r.get(0), r.get(3));
  }

  @Test
  void globalErrors() {
    List<Result> r =
        compile(
            true,
            List.of(
                fields("hasErrors", lit("global")),
                fields("errors", lit("global")),
                fields("hasErrors", new Expr.SelectionVar("global", P))));
    assertEquals(new Result("ctx.forms().hasErrors(\"owner\", \"global\")", "boolean", List.of()), r.get(0));
    assertEquals(
        new Result("ctx.forms().errors(\"owner\", \"global\")", "java.util.List<java.lang.String>", List.of()),
        r.get(1));
    // Only the literal 'global' names the object's errors; *{global} is a property path.
    assertTrue(
        String.join("; ", r.get(2).errors()).contains("'global' is not a property of com.example.Owner"),
        r.get(2).toString());
    Result outside = compile(false, List.of(fields("hasErrors", lit("global")))).getFirst();
    assertTrue(String.join("; ", outside.errors()).contains("can only be used inside t:object"), outside.toString());
  }

  @Test
  void negative() {
    List<Result> r =
        compile(
            true,
            List.of(
                fields("hasErrors", lit("nope")),
                fields("hasErrors", new Expr.Var("lastName", P)),
                fields("hasErrors"),
                fields("delete", lit("lastName")),
                fields("hasErrors", lit("a b")),
                fields("errors", lit("address.zip"))));
    Function<Integer, String> err = i -> String.join("; ", r.get(i).errors());
    assertTrue(err.apply(0).contains("'nope' is not a property of com.example.Owner"), err.apply(0));
    assertTrue(err.apply(1).contains("takes one string literal"), err.apply(1));
    assertTrue(err.apply(2).contains("takes one string literal"), err.apply(2));
    assertTrue(err.apply(3).contains("unknown function fields.delete"), err.apply(3));
    assertTrue(err.apply(4).contains("is not a field path"), err.apply(4));
    assertTrue(err.apply(5).contains("'zip' is not a property of com.example.Address"), err.apply(5));
  }

  @Test
  void outsideObjectIsAnError() {
    Result r = compile(false, List.of(fields("hasErrors", lit("*")))).getFirst();
    assertTrue(String.join("; ", r.errors()).contains("can only be used inside t:object"), r.toString());
  }
}
