package dev.tessera.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlerTest {

  @TempDir Path tmp;

  @Test
  void pagesFileSkipsCommentsAndKeepsSpacesInForms() throws Exception {
    Path pages = Files.writeString(tmp.resolve("pages.txt"), """
        # comment

        GET /owners?lastName=
          POST /owners/new firstName=Ann&lastName=O'Neil <b>&address=1 Main St
        """);
    List<Crawler.Request> requests = Crawler.readPages(pages);
    assertEquals(2, requests.size());
    assertEquals(new Crawler.Request("GET /owners?lastName=", "GET", "/owners?lastName=", ""),
        requests.get(0));
    assertEquals("firstName=Ann&lastName=O'Neil <b>&address=1 Main St", requests.get(1).form());
  }

  @Test
  void badLineNamesFileAndLine() throws Exception {
    Path pages = Files.writeString(tmp.resolve("pages.txt"), "GET /\nPUT /x\n");
    IOException e = assertThrows(IOException.class, () -> Crawler.readPages(pages));
    assertTrue(e.getMessage().contains("pages.txt:2:"), e.getMessage());
  }

  @Test
  void formBodyIsReEncodedAndGetsTheCsrfToken() {
    assertEquals("lastName=O%27Neil+%3Cb%3E&phone=%2B996&a=b+c&_csrf=t%2F1",
        Crawler.formBody("lastName=O'Neil <b>&phone=%2B996&a=b+c", "t/1"));
    assertEquals("_csrf=mine", Crawler.formBody("_csrf=mine", "t"), "an explicit _csrf wins");
    assertEquals("a=", Crawler.formBody("a", null));
    assertEquals("p=100%25", Crawler.formBody("p=100%", null), "a raw % is sent as written");
  }

  @Test
  void csrfComesFromTheHiddenInput() {
    assertEquals("abc", Crawler.csrf("<form><input value='abc' type=hidden name=_csrf></form>"));
    assertNull(Crawler.csrf("<form><input name=csrf value=x></form>"));
  }
}
