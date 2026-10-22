package dev.tessera.compiler.codegen;

import dev.tessera.compiler.codegen.attr.AttrProcessor;
import dev.tessera.compiler.codegen.attr.ClassAppendProcessor;
import dev.tessera.compiler.codegen.attr.EachProcessor;
import dev.tessera.compiler.codegen.attr.ErrorClassProcessor;
import dev.tessera.compiler.codegen.attr.ErrorsProcessor;
import dev.tessera.compiler.codegen.attr.FieldProcessor;
import dev.tessera.compiler.codegen.attr.FragmentProcessor;
import dev.tessera.compiler.codegen.attr.GenericAttrProcessor;
import dev.tessera.compiler.codegen.attr.IfProcessor;
import dev.tessera.compiler.codegen.attr.InsertReplaceProcessor;
import dev.tessera.compiler.codegen.attr.ObjectProcessor;
import dev.tessera.compiler.codegen.attr.RemoveProcessor;
import dev.tessera.compiler.codegen.attr.SwitchProcessor;
import dev.tessera.compiler.codegen.attr.TextProcessor;
import dev.tessera.compiler.codegen.attr.UtextProcessor;
import dev.tessera.compiler.codegen.attr.WithProcessor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The fixed attribute precedence (Thymeleaf's order, with t:fragment first so parameterized
 * fragments can drop themselves when rendered in place). Lower index runs first. Any
 * {@code t:<name>} not listed here and not in {@link #CORE} is a plain output attribute handled
 * by {@link GenericAttrProcessor} at the position of {@code GENERIC}.
 *
 * <p>This list is the only shared file between attribute families; do not reorder it without
 * calling it out in the PR.
 */
public final class AttributeProcessors {
  private AttributeProcessors() {}

  public record Entry(String name, AttributeProcessor processor) {}

  /** Placeholder name marking where generic {@code t:<attr>} attributes run. */
  public static final String GENERIC = "*";

  /** Handled by {@link TemplateGenerator} directly; only valid on the root element. */
  public static final Set<String> CORE = Set.of("params", "import");

  private static final InsertReplaceProcessor INSERT_REPLACE = new InsertReplaceProcessor();
  private static final IfProcessor IF = new IfProcessor();
  private static final SwitchProcessor SWITCH = new SwitchProcessor();

  public static final List<Entry> ORDER =
      List.of(
          new Entry("fragment", new FragmentProcessor()), // #14
          new Entry("insert", INSERT_REPLACE), // #14
          new Entry("replace", INSERT_REPLACE), // #14
          new Entry("each", new EachProcessor()), // #12
          new Entry("if", IF), // #12
          new Entry("unless", IF), // #12
          new Entry("switch", SWITCH), // #12
          new Entry("case", SWITCH), // #12
          new Entry("object", new ObjectProcessor()), // #15
          new Entry("with", new WithProcessor()), // #12
          new Entry("attr", new AttrProcessor()), // #13
          new Entry(GENERIC, new GenericAttrProcessor()), // #13
          new Entry("field", new FieldProcessor()), // #15
          new Entry("classappend", new ClassAppendProcessor()), // #13
          new Entry("errorclass", new ErrorClassProcessor()), // #15
          new Entry("text", new TextProcessor()), // #11
          new Entry("utext", new UtextProcessor()), // #11
          new Entry("errors", new ErrorsProcessor()), // #15
          new Entry("remove", new RemoveProcessor())); // #12

  /** Precedence index for a {@code t:} attribute local name. */
  public static int precedence(String localName) {
    int generic = -1;
    for (int i = 0; i < ORDER.size(); i++) {
      String n = ORDER.get(i).name();
      if (n.equals(localName)) {
        return i;
      }
      if (n.equals(GENERIC)) {
        generic = i;
      }
    }
    return generic;
  }

  /** The processor for a local name; empty for {@link #CORE} names. */
  public static Optional<AttributeProcessor> forName(String localName) {
    if (CORE.contains(localName)) {
      return Optional.empty();
    }
    return Optional.of(ORDER.get(precedence(localName)).processor());
  }
}
