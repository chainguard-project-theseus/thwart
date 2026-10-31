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

  /**
   * Java statements that render {@code node} and its subtree in {@code scope}, with their skeleton
   * (#366: everything generated code writes goes through {@link Emitted}).
   */
  Emitted renderNode(Node node, Scope scope);

  /**
   * Like {@link #renderNode} for an element, ignoring the listed {@code t:} attributes (local
   * names), e.g. {@code Set.of("fragment")} for a {@code ~{::tag}} selection helper. The element is
   * rendered standalone: where it lands is checked where it is inserted.
   */
  Emitted renderElement(Element element, Scope scope, Set<String> ignoredAttributes);

  /**
   * The body of fragment {@code f}'s method: {@link #renderElement} of its element without
   * {@code t:fragment}, with {@link #fragment} set to {@code f} meanwhile.
   */
  Emitted renderFragment(TemplateIndex.FragmentInfo f, Scope scope);

  /**
   * The fragment whose method is being generated, or null (the template's {@code render} method).
   * Its {@code Fragment} parameters are recorded as {@code Skeleton.ParamInsert}s where they are
   * inserted.
   */
  TemplateIndex.FragmentInfo fragment();

  /**
   * The lower-case name of the nearest element being generated whose tag is written, or null at
   * the top of a template or of an element rendered standalone: the element a text hole is in,
   * recorded in its {@code Skeleton.Hole}.
   */
  String hostElement();

  /**
   * Whether the element whose content is being generated is a {@code <script>} with a JSON type
   * ({@code Contexts.isJsonScript}): its SCRIPT values are JSON data, written by {@code Escapers.json}.
   * Other SCRIPT values are JavaScript, written by {@code Escapers.javaScript}.
   */
  boolean inJsonScript();

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
   * Whether htmx may request {@code element}'s {@code action} and swap the response into the page
   * (#258): a {@code <form>} in a statically boosted subtree of this template. Boosting from a
   * fragment caller, a layout or JavaScript is not seen.
   */
  boolean htmxSubmits(Element element);

  /**
   * Elements whose {@code ~{::tag}} helper is being generated, so mutually recursive selections
   * are reported instead of recursing forever. Compared by identity; callers add before rendering
   * and remove in a {@code finally}.
   */
  Set<Element> selecting();

  /**
   * The {@code RenderContext} expression to pass to markup inserted at the element being rendered
   * now: {@code ctx}, or {@code ctx} without its CSP nonce inside {@code <svg>}, {@code <math>} or
   * a raw text element, so a fragment's {@code <script>} and {@code <style>} get the nonce only
   * where a browser reads them as HTML (#335).
   */
  String insertionContext();
}
