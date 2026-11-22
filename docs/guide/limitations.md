# Known limitations

Gaps in Thwart today, with what to do instead.

- **Versions and build.** Spring 4.3+ / Boot 1.5+ on Servlet 3.0+; anything older is untested.
  Maven and Gradle. Hot reload restarts the application through Spring Boot DevTools ([Hot reload](migrate.md#hot-reload));
  without Boot there is none.
- **Messages.** `-Athwart.messages` reads bundles from the file system. Keys that only a jar or a
  database-backed `MessageSource` provides fail the build: give those pages a function that looks
  them up, unchecked.
- **View resolution.** Names match exactly, less one leading `/`. `page :: fragment` renders a
  fragment whose parameters a model can fill and whose root fits a response body; arguments and
  selectors in view names, and fragments with `Fragment` or `Field` parameters, fall through.
- **Mixed engines.** While the old engine is configured it renders every view Thwart doesn't
  know, so an app part-way through is protected only on the ported views.
- **Types.** `java.util.Date` and `Calendar` aren't view-safe: declare `Instant`, `ZonedDateTime`
  or `LocalDate`. `[[...]]` serializes only the properties of the static type and ignores Jackson
  annotations.
- **Client-side code.** What page JavaScript or a client framework does with the DOM is outside
  the guarantee; the starter's default CSP mitigates it. Client frameworks that compile the
  server's DOM as a template (Vue in-DOM templates, petite-vue, AngularJS) are unsupported.
- **Not supported, by design:** runtime template loading and Kotlin.
- **Expected output differences:** attributes set by `t:` may move within the tag; a `null`
  attribute value omits the attribute; `<script>` and `<style>` get a CSP
  nonce. `compare` ignores these.
- **Runtime errors** are model type mismatches, missing primitive parameters, reading a property
  of `null`, a `null` number in `<` or arithmetic, `null` link path variables and `ids.prev`
  before any input. Everything else is a build error.
- **Markup rejections** and their fixes are in this table. Verifier errors end with `[verifier:RULE]`:
  a spec HTML parser rejects markup a browser reads only by error recovery or where browsers
  build different trees. For an expression inside a fragment, the error is at the call site.

| Rule | What it means | Fix |
|---|---|---|
| `PARSE_ERROR` | The markup parses only by error recovery: a block in a `<p>`, a second `<body>`, content between `</head>` and `<body>`, content directly in a table, a stray end tag, a missing space between attributes, a literal `<` or `&` | Write the markup a browser reads without recovery: close the `<p>` first, write `&lt;` and `&amp;`, pass `~{::body}` with `t:remove="tag"` ([Fragments and layouts](templates.md#fragments-and-layouts)) |
| `UNBALANCED` | A `t:if`, `t:unless`, `t:case`, `t:each`, computed attribute or fragment leaves an element open or closed, or implies a `<tbody>`, `<head>` or `<body>`, or a computed attribute follows an unquoted attribute value | Close inside the body what it opens; write the `<tbody>` around rows, with the `t:if` or `t:unless` on it if there can be none; make a fragment library's root a `<t:block>` ([Fragments and layouts](templates.md#fragments-and-layouts)); quote the attribute value before a computed attribute (`class="a"`, not `class=a`) |
| `TEXT_IN_TABLE` | A value directly in a `<table>`, `<tbody>` or `<tr>`, where a browser moves it out of the table | Put it in a `<td>`, `<th>` or `<caption>` |
| `TEXT_OUTSIDE_BODY` | A value in `<head>` or after `</body>` | Move it into `<title>` or the body |
| `TEXT_IN_SCRIPT`, `JSON_NOT_IN_SCRIPT` | A value in a script or style position the compiler doesn't accept | A prelude declaration or a JSON block ([Output contexts](templates.md#output-contexts), Scripts); no expressions in `<style>` ([Output contexts](templates.md#output-contexts), CSS) |
| `TEXT_NOT_IN_TEXT` | A value inside a comment, a tag, or right after `&` or `<` | Move it into element text or a `t:` attribute; finish the character reference |
| `ATTRIBUTE_NOT_IN_VALUE` | Static markup around a computed attribute changes which attribute it lands in (an unclosed quote, a duplicate) | Fix the static tag; one attribute per name |
| `RAW_NOT_FLOW`, `RAW_CLOSES_ELEMENT` | A `SafeHtml`, HTML message or `Fragment` where its markup would leave its place: in a table part, in a `<p>` or heading, inside a link (for a message with links), in a `<select>` | Inline-only HTML in a `<p>`, heading, link or button: a `SafeInlineHtml` ([Output contexts](templates.md#output-contexts), Inline HTML); otherwise a `<div>` or a cell; split the message ([Messages and links](templates.md#messages-and-links)) |
| `VALUE_IN_FOREIGN` | A value, condition, loop, fragment or computed attribute inside or on `<svg>` or `<math>` (`t:if` or `t:replace` on the element itself is fine) | Static SVG. Set what changes outside it: a `t:class` on an HTML parent (`fill: currentColor`), a CSS variable (`<div t:style="\|--progress: ${n}\|">`), one `<svg>` per case under `t:if`, or `<img t:src="@{/icons/x.svg}">` |
| `CDATA_SECTION` | A `<![CDATA[` section | Write the text without it, with `&lt;` and `&amp;` |
| `VALUE_IN_RAW_TEXT` | A value inside `<noscript>` or `<iframe>` | Static content; move the value outside the element |
| (build error) `<xmp>`, `<plaintext>`, `<noembed>`, `<noframes>`, `<frameset>` | Obsolete elements whose parsing Thwart doesn't model | Escaped text (`&lt;`) in a `<pre>`; `<noscript>`; an ordinary layout or an `<iframe>` |
| `FOREIGN_IN_TABLE` | An `<svg>` or `<math>` inside a table or `<template>` | An `<img>` with the SVG file, or the SVG outside the table |
| `TABLE_IN_TEMPLATE` | A table part directly in `<template>` | Put the whole `<table>` in the `<template>` |
| `SELECT_CONTENT` | Anything but `<option>`, `<optgroup>` and `<hr>` in a `<select>` | Move it outside the `<select>` |
| `STRUCTURE_DEPENDS_ON_DATA` | A fragment that calls itself ever deeper (a recursive tree renderer) | A flat loop with depth classes, or `html.sanitize` to `SafeHtml` ([Output contexts](templates.md#output-contexts), Rich text) |
| `UNCALLED_FRAGMENT_FITS_NOWHERE` | Verifier-only: a fragment declared outside HTML content is the build error in the next row first | Declare it in HTML content |
| (build error) `t:fragment in <svg> content is not supported`, `~{::a} selects an element in <svg> content` | A `t:fragment`, or an element `~{::selector}` picks, inside `<svg>`, `<math>`, `<select>`, raw text (`<noscript>`, `<title>`, ...) or `t:remove="tag"` content | Declare the fragment outside it (directly under `<body>` or in its own file) and insert it there |
| (build error) `t:insert inside a <select t:field> is not supported` | `t:insert` or `t:replace` in a `<select t:field>`: inserted options would never be selected | Write the options inside the select (a `t:each`); for a partial, see [Rendering](templates.md#rendering) |
| (build error) `t:attr is not supported` | Thymeleaf's `th:attr`, which set several attributes from one value | One `t:<name>` per attribute: `t:attr="title=a, href=b"` becomes `t:title="a" t:href="b"` |
| (build error) `t:errorclass is not supported` | Thymeleaf's `th:errorclass`, which added a class when the `t:field` had errors | `t:classappend="fields.hasErrors(*{email}) ? 'has-error'"` with the element's `t:field` path; if the element has a `t:classappend` already, join both conditions into that one |
| (build error) `t:remove="body" is not supported` (also `all-but-first`, `none`) | A `t:remove` value other than `all` and `tag` | Edit the template to the result: delete the content, every child element but the first, or the attribute |
| (build error) `<t:csrf-meta> takes no attributes`, `<t:csrf-meta> takes no content` | An attribute (`t:if` included) or content on `<t:csrf-meta>`, which writes Spring Security's two CSRF `<meta>` tags itself | `<t:csrf-meta/>` alone; to write it on some pages only, put it in a `<t:block t:if="...">` ([Forms](templates.md#forms)) |
