package dev.tessera.compiler.messages;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MessageMarkupTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plain text",
        "a < b and c > d, 1 <2",
        "Hello <b>{0}</b>",
        "<p>One<p/>Two<br>Three<br/><hr />",
        "<ul><li>a<li>b</ul>",
        "<dl><dt>t<dd>d</dl>",
        "<a href=\"https://example.com/x?a=1&amp;b=2\" class=\"alert-link\" target=\"_blank\" rel=\"noopener\">x</a>",
        "<A HREF=\"mailto:a@example.com\">x</A>",
        "<a href=\"tel:+123\">x</a> <a href=\"#top\">top</a> <a href=\"x/y:z\">rel</a>",
        "<span title=\"a > b\" lang=\"en\" dir=\"ltr\" id=\"x\">t</span>",
        "Don''t {0,number,integer} {1,date,short} {2,time} {3,number,percent}",
        "'{0}' is literal",
        "&lt;{0}&gt; and &amp; {0}",
        "< {0}",
        "a <b>&lt;</b>",
        "<a href=\" https://ec.europa.eu/docsroom/documents/31041\">x</a>",
        "<a href=\"https://example.com/ \">x</a>",
        "<a href=\"\t\n\r\f https://example.com/x?a=1&amp;b=2 \n\">x</a>",
        "<a href=\" /relative \">x</a> <a href=\" \">empty</a>",
      })
  void accepts(String message) {
    assertNull(MessageMarkup.problem(message), message);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '^',
      quoteCharacter = '`',
      value = {
        "<script>alert(1)</script>^ <script> is not allowed",
        "<img src=x onerror=alert(1)>^ <img> is not allowed",
        "<svg onload=alert(1)>^ <svg> is not allowed",
        "<b ONCLICK=\"x\">y</b>^ attribute onclick is not allowed on <b>",
        "<b o'n'click=\"x\">y</b>^ attribute o is not allowed on <b>",
        "<b class=x>y</b>^ needs a quoted value",
        "<b class=\"x\"title=\"y\">z</b>^ must be separated by whitespace",
        "<b class=\"x>y</b>^ is not closed",
        "<a href=\"javascript:alert(1)\">x</a>^ only http, https, mailto, tel and relative",
        "<a href=\"JaVaScRiPt:x\">x</a>^ only http, https, mailto, tel and relative",
        "<a href=\" javascript:alert(1)\">x</a>^ only http, https, mailto, tel and relative",
        "<a href=\" data:text/html,x \">x</a>^ only http, https, mailto, tel and relative",
        "<a href=\"java\tscript:x\">x</a>^ whitespace or a control character",
        "<a href=\"java&#9;script:x\">x</a>^ the only character reference allowed",
        "<a href=\" https://example.com/a b\">x</a>^ whitespace or a control character",
        "<a href=\"\u0001https://example.com/\">x</a>^ whitespace or a control character",
        "<a href=\"https://example.com/\u0000\">x</a>^ whitespace or a control character",
        "<a href=\"javascript&colon;x\">x</a>^ the only character reference allowed",
        "<a href=\"data:text/html,x\">x</a>^ only http, https, mailto, tel and relative",
        "<b title=\"{0}\">x</b>^ a placeholder may not be inside a tag",
        "<{0}>^ a placeholder may not follow '<'",
        "a <^ may not end with '<'",
        "x<^ may not end with '<'",
        "<a href='{0}'>x</a>^ needs a quoted value",
        "<a href='/x'>y</a>^ in the message as MessageFormat formats it",
        "{0,choice,0#a|1#b}^ is not allowed in a message rendered as HTML",
        "{0,number,#x}^ is not allowed in a message rendered as HTML",
        "{name}^ is not allowed in a message rendered as HTML",
        "{0^ has no closing",
        "<!-- c -->^ comments",
        "<![CDATA[x]]>^ comments",
        "<?php x ?>^ comments",
        "</b>^ has no matching start tag",
        "<b><i>x</b></i>^ </b> closes <i>",
        "<div>x^ <div> is never closed",
        "<div/>^ <div> is never closed",
        "</ b>^ malformed end tag",
        "<b/x>^ must be separated by whitespace",
        "<b\u00a0onclick=\"x\">y</b>^ malformed tag",
      })
  void rejects(String message, String reason) {
    String problem = MessageMarkup.problem(message);
    assertNotNull(problem, message);
    assertTrue(problem.contains(reason.trim()), message + " -> " + problem);
  }

  /** The CSV source above splits on line breaks, so these are listed here. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '^',
      value = {
        "\\n\\tjavascript:alert(1) ^ only http, https, mailto, tel and relative",
        "java\\nscript:x^ whitespace or a control character",
        " java\\rscript:x^ whitespace or a control character",
        "java\\fscript:x^ whitespace or a control character",
      })
  void rejectsLineBreaksInHref(String href, String reason) {
    String unescaped = href.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\f", "\f");
    String message = "<a href=\"" + unescaped + "\">x</a>";
    String problem = MessageMarkup.problem(message);
    assertNotNull(problem, message);
    assertTrue(problem.contains(reason.trim()), message + " -> " + problem);
  }
}
