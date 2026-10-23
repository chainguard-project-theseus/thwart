package dev.tessera.compiler.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.testing.Compilation;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.SafeHtml;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Compiles every example in {@code docs/guide.md} marked {@code <!-- compile: ... -->} with the
 * real processor, then renders the main ones. Markers: {@code compile: java} (a Java source),
 * {@code compile: template <path>} (a template under the templates root) and
 * {@code compile: messages} (the message bundle).
 */
class GuideExamplesTest {
  private static final Path GUIDE = Path.of("../docs/guide.md");
  private static final Pattern EXAMPLE =
      Pattern.compile("<!-- compile: (java|messages|template (\\S+)) -->\\n```\\w*\\n(.*?)\\n```", Pattern.DOTALL);
  private static final Pattern JAVA_NAME =
      Pattern.compile("(?s)package ([\\w.]+);.*?\\b(?:class|record|enum|interface) (\\w+)");

  private static Compilation compilation;
  private static Properties messages;
  private static int examples;

  @BeforeAll
  static void compileGuide() throws IOException {
    String guide = Files.readString(GUIDE);
    Compilation.Builder b = Compilation.builder().option("tessera.package", "com.example.templates");
    Matcher m = EXAMPLE.matcher(guide);
    messages = new Properties();
    StringBuilder bundle = new StringBuilder();
    while (m.find()) {
      examples++;
      String body = m.group(3);
      if (m.group(1).equals("java")) {
        Matcher n = JAVA_NAME.matcher(body);
        assertTrue(n.find(), "no package/type in java example:\n" + body);
        b.java(n.group(1) + "." + n.group(2), body);
      } else if (m.group(1).equals("messages")) {
        bundle.append(body).append('\n');
        messages.load(new StringReader(body));
      } else {
        b.template(m.group(2), body);
      }
    }
    compilation = b.messages(bundle.toString()).compile();
  }

  private static RenderContext ctx() {
    return new RenderContext(
        Locale.ROOT,
        (key, args) ->
            args.length == 0 || !messages.containsKey(key)
                ? messages.getProperty(key, "??" + key + "??")
                : new java.text.MessageFormat(messages.getProperty(key), Locale.ROOT).format(args),
        new DefaultLinks("/app"),
        Forms.NONE);
  }

  private static String render(String view, Map<String, ?> model) {
    return compilation.render(view, model, ctx());
  }

  private static Object customer() {
    return compilation.newInstance("com.example.guide.Customer", 7L, "Ann <b>");
  }

  @Test
  void everyExampleCompiles() {
    assertTrue(examples >= 20, "found only " + examples + " examples; is the marker syntax intact?");
    compilation.assertSuccess();
  }

  @Test
  void gtvgProductList() {
    Object comment = compilation.newInstance("com.example.gtvg.Comment", "Great");
    Object onions =
        compilation.newInstance("com.example.gtvg.Product", 1, "Onions", new BigDecimal("2.41"), true, List.of(comment));
    Object garlic =
        compilation.newInstance("com.example.gtvg.Product", 2, "Garlic & co", new BigDecimal("1.10"), false, List.of());
    String html = render("product/list", Map.of("prods", List.of(onions, garlic)));
    assertTrue(html.contains("<link rel=\"stylesheet\" type=\"text/css\" media=\"all\" href=\"/app/css/gtvg.css\" />"), html);
    assertTrue(html.contains("<tr class=\"odd\">\n        <td>Onions</td>\n        <td>2.41</td>\n        <td>yes</td>"), html);
    assertTrue(html.contains("<span>1</span> comment/s"), html);
    assertTrue(html.contains("href=\"/app/product/comments?prodId=1\">view</a>"), html);
    assertTrue(html.contains("<tr>\n        <td>Garlic &amp; co</td>\n        <td>1.10</td>\n        <td>no</td>"), html);
    assertTrue(html.contains("<span>0</span> comment/s\n          \n        </td>"), html);
    assertTrue(html.contains("<a href=\"/app/\">Return to home</a>"), html);
    assertTrue(!html.contains("xmlns:t") && !html.contains("t:"), html);
  }

  @Test
  void helloPage() {
    Object order = compilation.newInstance("com.example.guide.Order", "A1", new BigDecimal("9.99"));
    String html = render("guide/hello", Map.of("customer", customer(), "orders", List.of(order), "admin", true));
    assertTrue(html.contains("<h1>Hello, Ann &lt;b&gt;!</h1>"), html);
    assertTrue(html.contains("<li class=\"odd\">\n        1. <span>9.99</span>\n      </li>"), html);
    assertTrue(html.contains("<a href=\"/app/admin/customers/7\">Edit</a>"), html);
    assertTrue(!html.contains("No orders yet"), html);
  }

  @Test
  void messagesWithMarkup() {
    String html = render("guide/messages-html", Map.of("name", "<i>Ann</i>"));
    assertTrue(
        html.contains("<p class=\"alert\">This version is being phased out: see <a href=\"/phaseout\""
            + " class=\"alert-link\">the plan</a>.<p/>Save your work.</p>"),
        html);
    assertTrue(html.contains("<p>Hello <b>&lt;i&gt;Ann&lt;/i&gt;</b>!</p>"), html);
  }

  @Test
  void contexts() {
    Object account = compilation.newInstance("com.example.guide.Account", "a@example.com", List.of("</script>"));
    String html =
        render(
            "guide/contexts",
            Map.of("account", account, "bio", SafeHtml.unchecked("<em>hi</em>"), "homepage", "javascript:alert(1)"));
    assertTrue(html.contains("<a href=\"about:invalid#tessera\">a@example.com</a>"), html);
    assertTrue(html.contains("<section><em>hi</em></section>"), html);
    assertTrue(html.contains("data-email=\"a@example.com\""), html);
    assertTrue(html.contains("const roles = [\"\\u003c\\/script\\u003e\"];"), html);
    assertTrue(html.contains("<script src=\"/app/js/app.js\"></script>"), html);
  }

  @Test
  void functions() {
    Object order = compilation.newInstance("com.example.guide.Order", "ORD-12345678", new BigDecimal("9.5"));
    String html = render("guide/functions", Map.of("order", order));
    assertEquals("<p>\n  Total: <b>€9.50</b>\n  (<span>ORD-1...</span>)\n</p>", html.strip());
  }

  @Test
  void fragmentsAndLayout() {
    String cards = render("guide/cards-page", Map.of("customer", customer()));
    assertTrue(cards.contains("<section class=\"card\">\n    <h3>Profile</h3>\n    <dl><dt>Name</dt><dd>Ann &lt;b&gt;</dd></dl>"), cards);
    assertTrue(cards.contains("<p><span>Ann &lt;b&gt;</span></p>"), cards);
    assertTrue(cards.contains("<footer>© Example</footer>"), cards);

    String home = render("guide/home", Map.of("customer", customer()));
    assertTrue(home.contains("<title>Home - Example</title>"), home);
    // <body t:remove="tag"> inserts only the body's children: no <body> inside the layout's <body>.
    assertTrue(home.contains("<div class=\"container\">\n  <h1>Welcome, Ann &lt;b&gt;</h1>\n</div>"), home);
    assertEquals(1, home.split("<body", -1).length - 1, home);
  }

  @Test
  void forms() throws Exception {
    Object address = compilation.newInstance("com.example.guide.Address", "Main St");
    Object[] plans = compilation.classLoader().loadClass("com.example.guide.Plan").getEnumConstants();
    Object pro = compilation.enumConstant("com.example.guide.Plan", "PRO");
    Object signup = compilation.newInstance("com.example.guide.Signup", "a@example.com", pro, true, address);
    String profile = render("guide/profile", Map.of("signup", signup));
    assertTrue(profile.contains("<label for=\"email\">Email</label>"), profile);
    assertTrue(profile.contains("id=\"email\" name=\"email\" value=\"a@example.com\""), profile);
    assertTrue(profile.contains("name=\"address.street\" value=\"Main St\""), profile);

    String form = render("guide/signup", Map.of("signup", signup, "plans", List.of(plans)));
    assertTrue(form.contains("<form method=\"post\" action=\"/app/signup\">"), form);
    assertTrue(form.contains("<option value=\"BASIC\">BASIC</option>"), form);
    assertTrue(form.contains("<option value=\"PRO\" selected=\"selected\">PRO</option>"), form);
    assertTrue(form.contains("value=\"a@example.com\""), form);
    assertTrue(form.contains("checked=\"checked\""), form);
    assertTrue(form.contains(">Main St</textarea>"), form);
  }
}
