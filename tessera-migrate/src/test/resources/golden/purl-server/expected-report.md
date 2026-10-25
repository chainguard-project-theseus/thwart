# tessera-migrate report

Converted 3 template(s); 3 item(s) need manual attention.

## error.html

- `error.html:6`: inserts the recursive rich-text fragment `fragments/markup :: nodes` (see `fragments/markup.html:3`); replace the insert with `t:utext="html.sanitize(text)"` over the HTML string its nodes were parsed from (or `html.sanitizeRich`, or an application `HtmlPolicy`)

## fragments/markup.html

- `fragments/markup.html:3`: fragment `fragments/markup :: nodes` rebuilds rich text from a tree parsed in Java: it inserts itself under a `t:switch` on the tag name, so the data decides which element nests in which, and the compiler rejects that. Keep the stored HTML as a `String` and print it with `t:utext="html.sanitize(text)"` where the fragment is inserted (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace for other classes or images), then delete this fragment and the Java code that builds its nodes (guide section 4.10, Rich text)

## purlinfo.html

- `purlinfo.html:4`: inserts the recursive rich-text fragment `fragments/markup :: nodes` (see `fragments/markup.html:3`); replace the insert with `t:utext="html.sanitize(text)"` over the HTML string its nodes were parsed from (or `html.sanitizeRich`, or an application `HtmlPolicy`)
