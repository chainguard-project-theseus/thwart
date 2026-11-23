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
    for (String expr : List.of("m.set[0].name", "m.all[0].name")) {
      compile("<p t:text=\"" + expr + "\">x</p>")
          .assertError("a.html:1:", "cannot index java.util.", "has no defined order", "Loop over it with t:each");
    }
    for (String expr : List.of("m.set.first.name", "m.set.last.name", "m.all.getFirst().name")) {
      compile("<p t:text=\"" + expr + "\">x</p>")
          .assertError("a.html:1:", "works on a List, not on java.util.", "has no defined order", "give the view a List");
    }
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
