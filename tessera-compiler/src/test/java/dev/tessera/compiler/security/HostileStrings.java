package dev.tessera.compiler.security;

import java.util.List;
import java.util.Random;

/** Known XSS vectors and a generator of random strings biased towards context-breaking input. */
final class HostileStrings {
  private HostileStrings() {}

  /** Classic vectors for every output context. */
  static final List<String> VECTORS =
      List.of(
          "\"><script>alert(1)</script>",
          "' onmouseover='alert(1)",
          "\" onmouseover=\"alert(1)",
          "</script><script>alert(1)</script>",
          "</SCRIPT ><script>alert(1)//",
          "</title><script>alert(1)</script>",
          "</textarea><script>alert(1)</script>",
          "</xmp><script>alert(1)</script>",
          "</noscript><img src=x onerror=alert(1)>",
          "<!--<script>",
          "--><script>alert(1)</script>",
          "]]><script>alert(1)</script>",
          "javascript:alert(1)",
          "JaVaScRiPt:alert(1)",
          " javascript:alert(1)",
          "java\tscript:alert(1)",
          "java\nscript:alert(1)",
          "\u0000javascript:alert(1)",
          "\u0001\u0002javascript:alert(1)",
          "vbscript:msgbox(1)",
          "data:text/html,<script>alert(1)</script>",
          "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
          "&#106;avascript:alert(1)",
          "javascript&colon;alert(1)",
          "jav&#x09;ascript:alert(1)",
          " alert(1) ",
          "*/alert(1)/*",
          "/;alert(1);//",
          "`+alert(1)+`",
          "${alert(1)}",
          "\\\";alert(1);//",
          "\\u003cscript\\u003e",
          "𐀀\ud800",
          "\udfff<",
          "&amp;lt;script&amp;gt;",
          "%3Cscript%3E",
          "a b&c=d#e?f+g/h",
          "../../etc/passwd",
          "//evil.example/x.js",
          "");

  /** Pieces that each mean something in at least one context. */
  private static final String[] PIECES = {
    "<", ">", "&", "\"", "'", "`", "$", "{", "}", "/", "\\", "*", ":", ";", "=", "#", "?", "%", "+", " ",
    "\t", "\n", "\r", "\u0000", "\u0001", "\u001f", "\u007f", " ", " ", "\ud800", "\udc00",
    "😀", "é", "﻿", "script", "</script", "<!--", "-->", "]]>", "javascript", "data",
    "vbscript", "http", "mailto", "&#", "&lt;", "&amp;", "\\u003c", "*/", "/*", "//", "${", "onerror=",
    "a", "Z", "0"
  };

  /** A random string of up to {@code maxPieces} pieces and arbitrary chars. */
  static String random(Random rnd, int maxPieces) {
    StringBuilder sb = new StringBuilder();
    int n = rnd.nextInt(maxPieces + 1);
    for (int i = 0; i < n; i++) {
      int k = rnd.nextInt(10);
      if (k < 7) {
        sb.append(PIECES[rnd.nextInt(PIECES.length)]);
      } else if (k < 9) {
        sb.append((char) rnd.nextInt(0x80));
      } else {
        sb.append((char) rnd.nextInt(0x10000));
      }
    }
    return sb.toString();
  }
}
