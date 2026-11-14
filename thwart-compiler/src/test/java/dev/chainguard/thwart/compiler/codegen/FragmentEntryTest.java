package dev.chainguard.thwart.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chainguard.thwart.compiler.testing.Compilation;
import dev.chainguard.thwart.runtime.internal.TemplateEntry;
import dev.chainguard.thwart.runtime.TemplateModelException;
import dev.chainguard.thwart.runtime.TemplateRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Registry entries for fragments (#131): {@code return "owners/list :: rows";} renders the fragment
 * {@code rows} of {@code owners/list} from the model, which fills the fragment's own declared
 * parameters by name, as a page's {@code t:params}. Only fragments the verifier accepts as a
 * response body, and whose parameters a model can supply, get one.
 */
class FragmentEntryTest {
  private static final String PAGE =
      "<!DOCTYPE html>\n<html xmlns:t=\"https://thwart.chainguard.dev\" t:import=\"java.util.List\" t:params=\"String title\">\n"
          + "<body>\n<h1 t:text=\"title\">T</h1>\n"
          + "<table><tbody>\n"
          + "<tr t:fragment=\"rows(List<String> names)\" t:each=\"n : names\"><td t:text=\"n\">Ann</td></tr>\n"
          + "</tbody></table>\n"
          + "<ul><li t:fragment=\"item(Long id, int rank)\">[[${id}]]/[[${rank}]]</li></ul>\n"
          + "<footer t:fragment=\"copyright\">(c) Example</footer>\n"
          + "<section t:fragment=\"card(String title, Fragment content)\"><h2 t:text=\"title\">x</h2>"
          + "<div t:replace=\"content\"></div></section>\n"
          + "<div t:replace=\"~{:: card('Hi', ~{:: copyright})}\"></div>\n"
          + "</body>\n</html>\n";

  private static Set<String> names(java.util.Collection<TemplateEntry> entries) {
    return entries.stream().map(TemplateEntry::name).collect(Collectors.toSet());
  }

  @Test
  void aFragmentRendersFromTheModelByItsDeclaredParams() {
    Compilation c = Compilation.builder().template("owners/list.html", PAGE).compile();
    assertEquals(
        "<tr><td>Ann</td></tr><tr><td>&lt;b&gt;</td></tr>",
        c.render("owners/list :: rows", Map.of("names", List.of("Ann", "<b>"), "title", "unused")));
    assertEquals("<li>7/3</li>", c.render("owners/list :: item", Map.of("id", 7L, "rank", 3)));
    // No params: it renders the same from any model.
    assertEquals("<footer>(c) Example</footer>", c.render("owners/list :: copyright", Map.of()));
  }

  @Test
  void fragmentEntriesAreKeptApartFromTheTemplates() {
    TemplateRegistry r = Compilation.builder().template("owners/list.html", PAGE).compile().registry();
    assertEquals(Set.of("owners/list"), names(r.all()));
    // A Fragment parameter is one only a call supplies: no entry for card.
    assertEquals(Set.of("owners/list :: rows", "owners/list :: item", "owners/list :: copyright"), names(r.fragments()));
    TemplateEntry rows = r.find("owners/list :: rows").orElseThrow();
    assertEquals(List.of(new TemplateEntry.Param("names", "java.util.List<java.lang.String>")), rows.params());
    // Exact names only: the registry never parses one.
    for (String name : List.of("owners/list :: card", "owners/list::rows", "owners/list :: rows(names)",
        "owners/list :: #rows", "owners/list :: tr", "owners/list ::  rows", "owners/list :: nope", "owners/list :: ")) {
      assertTrue(r.find(name).isEmpty(), name);
    }
  }

  @Test
  void aModelValueOfTheWrongTypeFailsNamingTheFragment() {
    Compilation c = Compilation.builder().template("owners/list.html", PAGE).compile();
    TemplateModelException e = assertThrows(TemplateModelException.class,
        () -> c.render("owners/list :: item", Map.of("id", "7", "rank", 3)));
    assertTrue(e.getMessage().contains("'owners/list :: item'"), e.getMessage());
    assertTrue(e.getMessage().contains("'id' must be java.lang.Long"), e.getMessage());
    // A primitive parameter needs a value.
    e = assertThrows(TemplateModelException.class, () -> c.render("owners/list :: item", Map.of("id", 7L)));
    assertTrue(e.getMessage().contains("'rank' must be int but is missing"), e.getMessage());
  }

  @Test
  void aFieldParameterGetsNoEntry() {
    Compilation c = Compilation.builder()
        .template("f.html", "<div xmlns:t=\"https://thwart.chainguard.dev\">"
            + "<label t:fragment=\"field(Field f, String label)\" t:text=\"label\">x</label>"
            + "<span t:fragment=\"plain(String label)\" t:text=\"label\">x</span></div>")
        .compile();
    assertEquals(Set.of("f :: plain"), names(c.registry().fragments()));
  }

  @Test
  void aCalledFragmentTheVerifierRejectsAsAResponseGetsNoEntry() {
    // A call checks a fragment only where the call writes it. On its own, a <tbody> root parses in
    // <body> (#406: it implies no other context), where a browser drops the tag: the verifier
    // rejects it there, so it gets no entry and the build still succeeds. The <tr> and <dd>
    // fragments fit their contexts and get one. (A <head> or <body> fragment can't be inserted
    // since #682: it changes the document state; pages pass their parts to a layout on <html>.)
    String x = "xmlns:t=\"https://thwart.chainguard.dev\"";
    Compilation c = Compilation.builder()
        .template("q.html", "<div " + x + "><table t:insert=\"~{l :: tb('T')}\"></table>"
            + "<table><tbody t:insert=\"~{l :: tr('T')}\"></tbody></table><dl t:insert=\"~{l :: dd('T')}\"></dl></div>")
        .template("l.html", "<!DOCTYPE html><html " + x + "><head><title>x</title></head>"
            + "<body><table><tbody t:fragment=\"tb(String t)\"><tr t:fragment=\"tr(String t)\">"
            + "<td t:text=\"t\">x</td></tr></tbody></table><dl><dd t:fragment=\"dd(String t)\" t:text=\"t\">x</dd></dl>"
            + "</body></html>")
        .compile();
    c.assertSuccess();
    assertEquals(Set.of("l :: tr", "l :: dd"), names(c.registry().fragments()));
    assertEquals("<tr><td>&lt;x&gt;</td></tr>", c.render("l :: tr", Map.of("t", "<x>")));
  }

  @Test
  void theUnverifiedPathHasNoFragmentEntries() {
    // Without the verifier (a test-only build), no fragment was checked as a response body.
    Compilation c = Compilation.builder().template("owners/list.html", PAGE).withoutVerifier().compile();
    assertTrue(c.registry().fragments().isEmpty());
    assertTrue(c.registry().find("owners/list :: rows").isEmpty());
  }
}
