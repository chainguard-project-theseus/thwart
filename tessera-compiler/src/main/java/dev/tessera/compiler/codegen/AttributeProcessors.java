package dev.tessera.compiler.codegen;

import dev.tessera.compiler.Backports;
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
import java.util.Objects;
import java.util.Set;

/**
 * The fixed attribute precedence (Thymeleaf's order, with t:fragment first so parameterized
 * fragments can drop themselves when rendered in place). Lower index runs first. Any
 * {@code t:<name>} not listed here and not in {@link #CORE} is a plain output attribute handled
 * by {@link GenericAttrProcessor} at the position of {@code GENERIC}.
 *
 * <p>The order is the precedence: changing it changes template semantics.
 */
public final class AttributeProcessors {
  private AttributeProcessors() {}

  private static final class Entry {
    private final String name;
    private final AttributeProcessor processor;

    private Entry(String name, AttributeProcessor processor) {
      this.name = name;
      this.processor = processor;
    }

    public String name() {
      return name;
    }

    public AttributeProcessor processor() {
      return processor;
    }
  }

  /** Placeholder name marking where generic {@code t:<attr>} attributes run. */
  private static final String GENERIC = "*";

  /** Handled by {@link TemplateGenerator} directly; only valid on the root element. */
  public static final Set<String> CORE = Backports.setOf("params", "import");

  private static final InsertReplaceProcessor INSERT_REPLACE = new InsertReplaceProcessor();
  private static final IfProcessor IF = new IfProcessor();
  private static final SwitchProcessor SWITCH = new SwitchProcessor();

  private static final List<Entry> ORDER =
      Backports.listOf(
          new Entry("fragment", new FragmentProcessor()),
          new Entry("insert", INSERT_REPLACE),
          new Entry("replace", INSERT_REPLACE),
          new Entry("each", new EachProcessor()),
          new Entry("if", IF),
          new Entry("unless", IF),
          new Entry("switch", SWITCH),
          new Entry("case", SWITCH),
          new Entry("object", new ObjectProcessor()),
          new Entry("with", new WithProcessor()),
          new Entry("attr", new AttrProcessor()),
          new Entry(GENERIC, new GenericAttrProcessor()),
          new Entry("field", new FieldProcessor()),
          new Entry("classappend", new ClassAppendProcessor()),
          new Entry("errorclass", new ErrorClassProcessor()),
          new Entry("text", new TextProcessor()),
          new Entry("utext", new UtextProcessor()),
          new Entry("errors", new ErrorsProcessor()),
          new Entry("remove", new RemoveProcessor()));

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

  /** The processor for a local name that is not one of the {@link #CORE} names. */
  public static AttributeProcessor forName(String localName) {
    if (CORE.contains(localName)) {
      throw new IllegalArgumentException("a core attribute has no processor: " + localName);
    }
    return ORDER.get(precedence(localName)).processor();
  }
}
