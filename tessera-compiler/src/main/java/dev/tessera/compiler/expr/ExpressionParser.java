package dev.tessera.compiler.expr;

import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.SourcePos;
import java.util.List;

/**
 * Lexer and recursive-descent parser for every micro-syntax inside {@code t:} attributes.
 * {@code start} is the position of the first character of {@code text} (usually
 * {@code Attribute.valuePos()}); node positions are computed with {@link SourcePos#advance}.
 * A surrounding {@code ${...}} is accepted and ignored everywhere an expression is expected.
 * On a syntax error each method reports a diagnostic naming what was expected and returns
 * {@code null}.
 *
 * <p>Rejected with a targeted message (these are SSTI vectors in Thymeleaf): {@code T(...)},
 * {@code new}, {@code __...__} preprocessing, {@code @bean} references, assignment, lambdas,
 * projection/selection ({@code .![]}, {@code .?[]}), and dynamic message keys or link paths.
 */
public final class ExpressionParser {

  /** A value expression ({@code t:text}, {@code t:if}, {@code t:href}, ...). */
  public Expr parseExpression(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseExpression");
  }

  /** {@code t:each}: {@code o : orders} or {@code o, stat : orders}. */
  public EachSpec parseEach(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseEach");
  }

  /** {@code t:with} / {@code t:attr}: {@code a=expr, b=expr}. */
  public List<Assignment> parseAssignments(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseAssignments");
  }

  /** {@code t:params}: {@code OwnerView owner, List<PetView> pets}. Empty text yields an empty list. */
  public List<ParamDecl> parseParams(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseParams");
  }

  /** {@code t:import}: comma-separated qualified names or {@code package.*}. */
  public List<String> parseImports(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseImports");
  }

  /** {@code t:fragment}: {@code name} or {@code name(Type a, Type b)}. */
  public FragmentSignature parseFragmentSignature(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseFragmentSignature");
  }

  /**
   * {@code t:insert}/{@code t:replace}: {@code ~{tpl :: name(args)}}, {@code tpl :: name},
   * {@code ~{:: name}}, {@code ~{this :: name}}. Always contains {@code ::}; a value without
   * {@code ::} (a Fragment-typed variable such as {@code template}) is a plain expression and
   * callers parse it with {@link #parseExpression}.
   */
  public FragmentRef parseFragmentRef(String text, SourcePos start, DiagnosticSink diagnostics) {
    throw new UnsupportedOperationException("TODO(#3): ExpressionParser.parseFragmentRef");
  }
}
