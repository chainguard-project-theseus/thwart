package dev.tessera.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.verify.Skeleton;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Adjacent static text is written by one call (#685), and the skeleton still has every piece. */
class EmittedTest {
  private static final SourcePos POS = new SourcePos("t.html", 1, 1);

  private static Emitted.Builder builder() {
    return new Emitted.Builder(text -> "C(" + text + ")");
  }

  private static List<String> statics(List<Skeleton.Node> nodes) {
    return nodes.stream().map(n -> ((Skeleton.Static) n).text()).collect(Collectors.toList());
  }

  @Test
  void textOfNestedBuildersIsMerged() {
    Emitted.Builder inner = builder();
    inner.text("<p>", POS);
    inner.text("a", POS);
    Emitted.Builder outer = builder();
    outer.text("<div>", POS);
    outer.add(inner.build());
    outer.add(Emitted.text("</p>", POS));
    outer.text("</div>", POS);
    Emitted e = outer.build();
    assertEquals("out.write(C(<div><p>a</p></div>));\n", e.java());
    assertEquals(List.of("<div>", "<p>", "a", "</p>", "</div>"), statics(e.nodes()));
  }

  @Test
  void textMovesOnlyAcrossBlankCode() {
    Emitted e =
        Emitted.concat(
            Emitted.text("a", POS), Emitted.code(" "), Emitted.text("b", POS), Emitted.code("x();"),
            Emitted.text("c", POS));
    assertEquals("out.write(\"ab\");\nx();out.write('c');\n", e.java());
    Emitted.Builder b = builder();
    b.text("<", POS);
    b.add(e);
    b.text(">", POS);
    assertEquals("out.write(C(<ab));\nx();\nout.write(C(c>));\n", b.build().java());
  }

  @Test
  void wrappedTextStaysInside() {
    Emitted.Builder b = builder();
    b.text("aa", POS);
    b.add(Emitted.wrap("{ ", Emitted.text("b", POS), "}"));
    b.text("cc", POS);
    assertEquals("out.write(C(aa));\n{ out.write('b');\n}\nout.write(C(cc));\n", b.build().java());
  }
}
