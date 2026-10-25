# tessera-migrate report

Converted 3 template(s); 3 item(s) need manual attention.

## coverage.html

- `coverage.html:4`: inserts the recursive rich-text fragment `fragments/html :: nodes` (see `fragments/html.html:4`); replace the insert with `t:utext="html.sanitize(text)"` over the HTML string its nodes were parsed from (or `html.sanitizeRich`, or an application `HtmlPolicy`)

## fragments/html.html

- `fragments/html.html:4`: fragment `fragments/html :: node` rebuilds rich text from a tree parsed in Java: it inserts itself under a `t:switch` on the tag name, so the data decides which element nests in which; that is a build error once the build-time context verifier is enforced. Keep the stored HTML as a `String` and print it with `t:utext="html.sanitize(text)"` where the fragment is inserted (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace for other classes or images), then delete this fragment and the Java code that builds its nodes (guide section 4.10, Rich text)

## statusPerUrl.html

- `statusPerUrl.html:5`: inserts the recursive rich-text fragment `fragments/html :: nodes` (see `fragments/html.html:4`); replace the insert with `t:utext="html.sanitize(text)"` over the HTML string its nodes were parsed from (or `html.sanitizeRich`, or an application `HtmlPolicy`)
