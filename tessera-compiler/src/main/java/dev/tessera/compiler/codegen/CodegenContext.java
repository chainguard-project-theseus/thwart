package dev.tessera.compiler.codegen;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.DiagnosticSink;
import dev.tessera.compiler.expr.Expr;
import dev.tessera.compiler.expr.ExpressionParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.types.CompiledExpr;
import dev.tessera.compiler.types.ExpressionCompiler;
import dev.tessera.compiler.types.Scope;
import java.util.Set;

/**
 * What attribute processors and codegen hooks may use while generating one template class.
 * Implemented by {@link TemplateGenerator}.
 *
 * <p>Code conventions (all generated code): render methods have the parameters
 * {@code RenderContext ctx, Output out}; all names are fully qualified; template variables use
 * {@code Variable.javaName()}; helper locals come from {@link #freshName} and always contain
 * {@code $}, so they can never collide with template identifiers.
 */
public interface CodegenContext {

  CompilerEnv env();

  default DiagnosticSink diagnostics() {
    return env().diagnostics();
  }

  TemplateIndex index();

  /** The template being generated. */
  TemplateIndex.TemplateInfo template();

  ExpressionParser parser();

  ExpressionCompiler expressions();

  /**
   * Parses {@code attr}'s value as a value expression; reports and returns null on error.
   * Processors that parse a non-attribute source or a non-expression grammar (each, with,
   * fragment refs, inlined text) use {@link #parser()} directly.
   */
  Expr parse(Attribute attr);

  /** Parses and compiles {@code attr}'s value in {@code scope}; reports and returns null on error. */
  CompiledExpr compile(Attribute attr, Scope scope);

  /** A unique Java identifier for a generated local, e.g. {@code o$3}. */
  String freshName(String hint);

  /** Java statements that render {@code node} and its subtree in {@code scope}. */
  String renderNode(Node node, Scope scope);

  /**
   * Like {@link #renderNode} for an element, ignoring the listed {@code t:} attributes (local
   * names), e.g. {@code Set.of("fragment")} when generating a fragment method.
   */
  String renderElement(Element element, Scope scope, Set<String> ignoredAttributes);

  /** Adds a member (method or constant) to the generated class. */
  void addMember(String javaCode);

  /**
   * The whitespace-only text immediately before {@code element} among its siblings (or the
   * whitespace after the last inlined expression of that text), which Thymeleaf repeats between
   * {@code t:each} iterations; null if there is none or the element is rendered standalone (a
   * fragment method or {@code ~{::selector}} helper).
   */
  String whitespaceBefore(Element element);

  /**
   * Whether htmx may request {@code element}'s {@code action} or {@code formaction} and swap the
   * response into the page (#258): a {@code <form>} in a statically boosted subtree of this
   * template, or another element in such a subtree or inside a {@code <form>} that is boosted or
   * has an htmx request attribute. Boosting from a fragment caller, a layout or JavaScript is not
   * seen.
   */
  boolean htmxSubmits(Element element);

  /**
   * Elements whose {@code ~{::tag}} helper is being generated, so mutually recursive selections
   * are reported instead of recursing forever. Compared by identity; callers add before rendering
   * and remove in a {@code finally}.
   */
  Set<Element> selecting();
}
