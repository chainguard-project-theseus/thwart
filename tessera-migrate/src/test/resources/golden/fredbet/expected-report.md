# tessera-migrate report

Converted 2 template(s); 2 item(s) need manual attention.

## fragments/rich_html.html

- `fragments/rich_html.html:5`: fragment `fragments/rich_html :: nodes` rebuilds rich text from a tree parsed in Java: it inserts itself under a `t:switch` on the tag name, so the data decides which element nests in which, and the compiler rejects that. Keep the stored HTML as a `String` and print it with `t:utext="html.sanitize(text)"` where the fragment is inserted (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace for other classes or images), then delete this fragment and the Java code that builds its nodes (guide section 4.10, Rich text)

## info/rules.html

- `info/rules.html:8`: inserts the recursive rich-text fragment `fragments/rich_html :: nodes` (see `fragments/rich_html.html:5`); replace the insert with `t:utext="html.sanitize(text)"` over the HTML string its nodes were parsed from (or `html.sanitizeRich`, or an application `HtmlPolicy`)
