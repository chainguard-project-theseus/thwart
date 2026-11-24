package dev.chainguard.thwart.compiler.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chainguard.thwart.compiler.testing.Compilation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #969: a Set has no index and no first or last; the error says why and what to write. */
class UnorderedCollectionTest {
  private static final String NS = "xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"com.example.*\" ";

  private static Compilation compile(String body) {
    return Compilation.builder()
        .java("com.example.D", "package com.example; public record D(String name) {}")
        .java(
            "com.example.M",
            "package com.example; import java.util.*; public record M(Set<D> set, Collection<D> all, List<D> list) {}")
        .template("a.html", "<div " + NS + "t:params=\"M m\">" + body + "</div>")
        .compile();
  }

  @Test
  void indexAndFirstOnASetSayWhyAndWhatToWrite() {
    compile("<p t:text=\"m.set[0].name\">x</p>")
        .assertError("a.html:1:", "cannot index java.util.Set", "a Set has no defined order", "Loop over it with t:each");
    for (String expr : List.of("m.set.first.name", "m.set.last.name", "m.set.getFirst().name")) {
      compile("<p t:text=\"" + expr + "\">x</p>")
          .assertError("a.html:1:", "works on a List, not on java.util.Set", "a Set has no defined order", "give the view a List");
    }
    // A Collection that isn't a Set may have an order, so the reason is only that it isn't a List.
    compile("<p t:text=\"m.all[0].name\">x</p>")
        .assertError("a.html:1:", "cannot index java.util.Collection", "only a List has an index", "Loop over it with t:each");
    compile("<p t:text=\"m.all.last.name\">x</p>").assertError("works on a List, not on java.util.Collection", "only a List");
  }

  @Test
  void aSetTypeThatDeclaresFirstStillReadsIt() {
    // A view type's own getFirst() wins, as before #969.
    Compilation c =
        Compilation.builder()
            .java(
                "com.example.Tags",
                "package com.example; @dev.chainguard.thwart.runtime.TemplateView public class Tags extends"
                    + " java.util.AbstractSet<String> { private final java.util.List<String> all;"
                    + " public Tags(java.util.List<String> all) { this.all = all; }"
                    + " public java.util.Iterator<String> iterator() { return all.iterator(); }"
                    + " public int size() { return all.size(); } public String getFirst() { return all.get(0); } }")
            .template("a.html", "<p " + NS + "t:params=\"Tags tags\" t:text=\"tags.first + tags.getFirst()\">x</p>")
            .compile()
            .assertSuccess();
    assertEquals("<p>aa</p>", c.render("a", Map.of("tags", c.newInstance("com.example.Tags", List.of("a", "b")))));
  }

  @Test
  void aListAndALoopOverASetStillWork() {
    Compilation c =
        compile("<p t:text=\"m.list[0].name + m.list.first.name + m.set.size()\">x</p><i t:each=\"d : m.set\" t:text=\"d.name\">d</i>")
            .assertSuccess();
    Object d = c.newInstance("com.example.D", "a");
    assertEquals(
        "<div><p>aa1</p><i>a</i></div>",
        c.render("a", Map.of("m", c.newInstance("com.example.M", Set.of(d), List.of(d), List.of(d)))));
  }
}
