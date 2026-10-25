package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link RichTextCheck}: recursive fragments that switch on a tag name are reported (the corpus
 * shapes are in the golden tests), ordinary recursive and switching fragments are not.
 */
class RichTextCheckTest {

  /** {@code file:line} of each item, in order; files and sources alternate in {@code pairs}. */
  private static List<String> check(String... pairs) {
    Map<String, String> templates = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      templates.put(pairs[i], pairs[i + 1]);
    }
    return RichTextCheck.check(templates).stream().map(i -> i.file() + ":" + i.line()).toList();
  }

  private static final String PAGE =
      "<html t:params=\"List<Node> body\"><body>\n<div t:insert=\"~{rich :: nodes(body)}\"></div></body></html>";

  // ---- reported ----

  @Test
  void selfInsertUnderTagSwitchIsReportedWithItsCallSites() {
    String rich = "<div>\n<t:block t:fragment=\"nodes(List<Node> nodes)\"><t:block t:each=\"n : nodes\" t:switch=\"n.tag\">"
        + "<p t:case=\"'p'\"><t:block t:replace=\"~{:: nodes(n.children)}\"></t:block></p>"
        + "<em t:case=\"'em'\"><t:block t:replace=\"~{this :: nodes(n.children)}\"></t:block></em>"
        + "</t:block></t:block></div>";
    assertEquals(List.of("rich.html:2", "page.html:2"), check("rich.html", rich, "page.html", PAGE));
    List<Report.Item> items = RichTextCheck.check(Map.of("rich.html", rich));
    assertEquals(String.format(ConversionRules.RICH_TEXT_FRAGMENT_MESSAGE, "rich :: nodes"), items.get(0).message());
  }

  @Test
  void thymeleafPrefixAndRecursionThroughAnotherTemplateAreReported() {
    String rich = "<div>\n<th:block th:fragment=\"nodes(nodes)\"><th:block th:each=\"n : ${nodes}\">"
        + "<th:block th:replace=\"lib/node :: node(${n})\"></th:block></th:block></th:block></div>";
    String node = "<div>\n\n<th:block th:fragment=\"node(n)\" th:switch=\"${n.tag}\">"
        + "<th:block th:case=\"'b'\"><b th:insert=\"~{rich :: nodes(${n.children})}\"></b></th:block>"
        + "<th:block th:case=\"'i'\"><i th:insert=\"~{rich :: nodes(${n.children})}\"></i></th:block>"
        + "</th:block></div>";
    assertEquals(List.of("lib/node.html:3", "page.html:2"),
        check("rich.html", rich, "lib/node.html", node, "page.html", PAGE));
  }

  // ---- not reported ----

  @Test
  void switchOverStaticTagsWithoutRecursionIsNotReported() {
    String icon = "<div t:fragment=\"badge(String kind, String label)\" t:switch=\"kind\">"
        + "<b t:case=\"'b'\" t:text=\"label\"></b><i t:case=\"'i'\" t:text=\"label\"></i>"
        + "<span t:case=\"*\" t:text=\"label\"></span></div>";
    String page = "<html t:params=\"String k\"><body><div t:replace=\"~{icon :: badge(k, 'x')}\"></div></body></html>";
    assertEquals(List.of(), check("icon.html", icon, "page.html", page));
  }

  @Test
  void recursiveMenuWithoutASwitchIsNotReported() {
    String menu = "<ul t:fragment=\"menu(List<Item> items)\"><li t:each=\"item : items\">"
        + "<a t:href=\"item.url\" t:text=\"item.title\"></a>"
        + "<t:block t:if=\"!item.children.isEmpty()\" t:replace=\"~{menu :: menu(item.children)}\"></t:block>"
        + "</li></ul>";
    assertEquals(List.of(), check("menu.html", menu));
  }

  @Test
  void recursiveTreeSwitchingOnDataNotTagsIsNotReported() {
    String tree = "<ul t:fragment=\"tree(List<Item> items)\"><t:block t:each=\"item : items\" t:switch=\"item.kind\">"
        + "<li t:case=\"'folder'\"><span t:text=\"item.name\"></span>"
        + "<t:block t:replace=\"~{tree :: tree(item.children)}\"></t:block></li>"
        + "<li t:case=\"'file'\"><a t:href=\"item.url\" t:text=\"item.name\"></a></li>"
        + "</t:block></ul>";
    assertEquals(List.of(), check("tree.html", tree));
  }

  @Test
  void recursionOutsideATagSwitchIsNotReported() {
    String menu = "<ul t:fragment=\"menu(List<Item> items)\"><li t:each=\"item : items\">"
        + "<t:block t:switch=\"item.style\"><b t:case=\"'b'\" t:text=\"item.title\"></b>"
        + "<i t:case=\"'i'\" t:text=\"item.title\"></i></t:block>"
        + "<t:block t:replace=\"~{menu :: menu(item.children)}\"></t:block></li></ul>";
    assertEquals(List.of(), check("menu.html", menu));
  }
}
