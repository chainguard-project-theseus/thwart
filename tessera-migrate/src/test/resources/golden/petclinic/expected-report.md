# tessera-migrate report

Converted 8 template(s); 38 item(s) need manual attention.

## fragments/inputField.html

- `fragments/inputField.html:3`: fragment `input` parameters declared as `Object` (`label`, `name`, `type`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)
- `fragments/inputField.html:10`: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)
- `fragments/inputField.html:11`: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)
- `fragments/inputField.html:16`: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)

## fragments/layout.html

- `fragments/layout.html:2`: fragment `layout` parameters declared as `Object` (`template`, `menu`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)
- `fragments/layout.html:34`: fragment `menuItem` parameters declared as `Object` (`link`, `active`, `title`, `glyph`, `text`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)
- `fragments/layout.html:35`: `__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field

## misc/scripts.html

- `misc/scripts.html:2`: `user` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/scripts.html:2`: `page` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/scripts.html:5`: `"[[${user.name}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:6`: `'[[${user.id}]]'` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:7`: `[[${user.name}]]` inside a quoted JavaScript string is a build error in Tessera (it writes a JSON value with its own quotes); rewrite as `"text " + [[${user.name}]] + " text"`
- `misc/scripts.html:11`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/scripts.html:12`: `` `[[${page.title}]]` `` in a script is a template literal, and `[[...]]` inside it is a build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a tagged template keep them and write ``tag`${[[...]]}` ``
- `misc/scripts.html:13`: `` `[[${page.path}]]` `` in a script is a template literal, and `[[...]]` inside it is a build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a tagged template keep them and write ``tag`${[[...]]}` ``
- `misc/scripts.html:14`: `"[[${page.number}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:16`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/scripts.html:19`: `"[[${page.title}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:20`: `"[[${user.name}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)

## misc/unsupported.html

- `misc/unsupported.html:2`: `user` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/unsupported.html:3`: `layout:decorate` belongs to the `layout:` dialect, which Tessera does not support; port it by hand
- `misc/unsupported.html:5`: `T(...)` static class access is not supported (never); expose the value through a view type or a `@TemplateFunction`
- `misc/unsupported.html:6`: Spring bean references (`@bean`) are not supported (never); pass the value as a parameter
- `misc/unsupported.html:7`: Thymeleaf utility object `#ctx` has no Tessera equivalent; use a `@TemplateFunctions` namespace or a view-type property
- `misc/unsupported.html:10`: collection selection/projection is not supported; compute the collection in Java
- `misc/unsupported.html:11`: `th:onclick` is a build error by design (no dynamic event handlers); use a `data-*` attribute read by a script
- `misc/unsupported.html:11`: `th:style` is not converted; rewrite it as `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend` or a `t:data-*` attribute
- `misc/unsupported.html:12`: `sec:authorize` belongs to the `sec:` dialect, which Tessera does not support; port it by hand
- `misc/unsupported.html:14`: `data-th-text` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:text`
- `misc/unsupported.html:15`: object creation with `new` is not supported (never); compute the value in Java
- `misc/unsupported.html:16`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/unsupported.html:19`: `[(...)]` prints `user.bio` without escaping; `t:utext` takes a `SafeHtml`. For stored HTML write `t:utext="html.sanitize(user.bio)"` (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace; guide section 4.10, Rich text), never a recursive fragment over a tree parsed in Java; for markup built in Java return a `SafeHtml`; for plain text use `t:text`

## owners/createOrUpdateOwnerForm.html

- `owners/createOrUpdateOwnerForm.html:1`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type

## owners/ownerDetails.html

- `owners/ownerDetails.html:3`: `message` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownerDetails.html:3`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type

## owners/ownersList.html

- `owners/ownersList.html:3`: `listOwners` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:3`: `totalPages` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:3`: `currentPage` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:23`: `__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field
