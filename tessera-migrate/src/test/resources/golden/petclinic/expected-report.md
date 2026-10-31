# tessera-migrate report

Converted 9 template(s); 71 item(s) need manual attention.

## fragments/inputField.html

- `fragments/inputField.html:3`: fragment `input` parameters declared as `Object` (`label`, `name`, `type`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)
- `fragments/inputField.html:10`: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)
- `fragments/inputField.html:11`: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)
- `fragments/inputField.html:16`: dynamic form-field path `*{__...__}` is not supported (preprocessing); inline one element per field (a typed alternative is tracked in #43)

## fragments/layout.html

- `fragments/layout.html:2`: fragment `layout` parameters declared as `Object` (`template`, `menu`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)
- `fragments/layout.html:34`: fragment `menuItem` parameters declared as `Object` (`link`, `active`, `title`, `glyph`, `text`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`)
- `fragments/layout.html:35`: `__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field

## misc/fixits.html

- `misc/fixits.html:2`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:2`: `swap` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:2`: `selector` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:2`: `callback` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:5`: `th:hx-target` was rewritten to `t:data-hx-target`: `hx-target` is not a known attribute, which is a build error for a computed attribute in Tessera, and htmx reads `data-hx-target` the same way
- `misc/fixits.html:5`: `th:hx-swap` was rewritten to `t:data-hx-swap`: `hx-swap` is not a known attribute, which is a build error for a computed attribute in Tessera, and htmx reads `data-hx-swap` the same way
- `misc/fixits.html:6`: `hx-select` in `th:attr` was rewritten to `data-hx-select`: `hx-select` is not a known attribute, which is a build error for a computed attribute in Tessera, and htmx reads `data-hx-select` the same way
- `misc/fixits.html:7`: `th:up-on-loaded`: `up-on-loaded` is not a known attribute, and a computed attribute must be a standard HTML, SVG, MathML or ARIA attribute, a `data-*` attribute or a plain prop on a custom element (a build error in Tessera); if it is data for your script, write `th:data-up-on-loaded`; if a framework reads it, write it as a static attribute
- `misc/fixits.html:10`: no space before attribute `class` (a parse error, which the build rejects): a space was added, which a browser reads the same
- `misc/fixits.html:13`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/fixits.html:15`: `/*[[${owner.id}]]*/ 0` in a script was rewritten to `[[${owner.id}]]`: Thymeleaf's natural template wrote the value in place of the comment and dropped the default `0`, and `[[...]]` inside a comment is a build error in Tessera
- `misc/fixits.html:15`: `[[${owner.id}]]` in a script was moved into `var _t1 = [[${owner.id}]];` at the top of the script and replaced by `_t1`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t1` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/fixits.html:22`: `[[${owner.birthDate}]]` in a script was moved into `var _t2 = [[${owner.birthDate}]];` at the top of the script and replaced by `_t2`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t2` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/fixits.html:23`: `[[${owner.id}]]` in a script was moved into `var _t3 = [[${owner.id}]];` at the top of the script and replaced by `_t3`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t3` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/fixits.html:24`: `[[${owner.lastName}]]` in a script was moved into `var _t4 = [[${owner.lastName}]];` at the top of the script and replaced by `_t4`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t4` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/fixits.html:25`: `[[${owner.firstName}]]` in a script was moved into `var _t5 = [[${owner.firstName}]];` at the top of the script and replaced by `_t5`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t5` is a global variable in a classic script, so rename it if another script on the page uses that name; it was inside a quoted string, which is now joined with `+`: a non-string value renders as JSON (`null`, not an empty string)
- `misc/fixits.html:26`: `[[${owner.telephone}]]` in a script was moved into `var _t6 = [[${owner.telephone}]];` at the top of the script and replaced by `_t6`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t6` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/fixits.html:27`: `[[${owner.visits}]]` in a script was moved into `var _t7 = [[${owner.visits}]];` at the top of the script and replaced by `_t7`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t7` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/fixits.html:28`: `[[${owner.city}]]` inside a JavaScript regular expression is a build error in Tessera and was not rewritten: declare the value at the top of the script (`var v = [[...]];`) and build the pattern with `new RegExp(v)`

## misc/scripts.html

- `misc/scripts.html:2`: `user` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/scripts.html:2`: `page` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/scripts.html:5`: `"[[${user.name}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:6`: `'[[${user.id}]]'` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:7`: `[[${user.name}]]` in a script was moved into `var _t1 = [[${user.name}]];` at the top of the script and replaced by `_t1`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t1` is a global variable in a classic script, so rename it if another script on the page uses that name; it was inside a quoted string, which is now joined with `+`: a non-string value renders as JSON (`null`, not an empty string)
- `misc/scripts.html:8`: `[[${user.name}]]` inside a JavaScript comment is a build error in Tessera (its value would be written inside the comment and the page would never use it); move it out of the comment: Thymeleaf's natural template `var a = /*[[${x}]]*/ 'd';` becomes `var a = [[${x}]];`
- `misc/scripts.html:9`: `[[${user.roles}]]` in a script was moved into `var _t2 = [[${user.roles}]];` at the top of the script and replaced by `_t2`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t2` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/scripts.html:11`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/scripts.html:12`: `` `[[${page.title}]]` `` in a script is a template literal, and `[[...]]` inside it is a build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a tagged template declare the value above the code (`var v = [[...]];`) and write ``tag`${v}` `` after it
- `misc/scripts.html:13`: `` `[[${page.path}]]` `` in a script is a template literal, and `[[...]]` inside it is a build error in Tessera (it writes a JSON value with its own quotes); drop the backticks, or for a tagged template declare the value above the code (`var v = [[...]];`) and write ``tag`${v}` `` after it
- `misc/scripts.html:14`: `"[[${page.number}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:14`: `[[${page.number}]]` in a script was moved into `var _t3 = [[${page.number}]];` at the top of the script and replaced by `_t3`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t3` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/scripts.html:16`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/scripts.html:19`: `"[[${page.title}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:19`: `[[${page.title}]]` in a script was moved into `var _t4 = [[${page.title}]];` at the top of the script and replaced by `_t4`: in a Tessera script `[[...]]` is allowed only as the whole value of a declaration at the top, before any other code; `_t4` is a global variable in a classic script, so rename it if another script on the page uses that name
- `misc/scripts.html:20`: `"[[${user.name}]]"` in a script was unquoted: in Tessera `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)

## misc/unsupported.html

- `misc/unsupported.html:2`: `user` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/unsupported.html:3`: `layout:decorate` belongs to the `layout:` dialect, which Tessera does not support; port it by hand
- `misc/unsupported.html:5`: `T(...)` static class access is not supported (never); compute the value in the controller, add it to the model and declare it in `t:params`, or call a static `@TemplateFunction`
- `misc/unsupported.html:6`: Spring bean references (`@bean`) are not supported (never); call the bean in the controller (or in a `@ModelAttribute` method of a `@ControllerAdvice` for a value every page needs), add the result to the model and declare it in `t:params`
- `misc/unsupported.html:7`: `#ctx.request` reads Thymeleaf's evaluation context, which Tessera does not have; a template sees only its `t:params`: read the parameter by name, and add any value the controller does not provide to the model and to `t:params` (guide section 1)
- `misc/unsupported.html:10`: collection selection/projection is not supported; compute the collection in Java
- `misc/unsupported.html:11`: `th:onclick` is a build error by design (no dynamic event handlers); use a `data-*` attribute read by a script
- `misc/unsupported.html:11`: `th:style` is not converted; rewrite it as `t:style` from literals, numbers and `css.color(...)`/`css.length(...)`/`css.url(...)` (guide section 4.4), otherwise use `t:classappend` or a `t:data-*` attribute
- `misc/unsupported.html:12`: `sec:authorize` belongs to the `sec:` dialect, which Tessera does not support; port it by hand
- `misc/unsupported.html:14`: `data-th-text` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:text`
- `misc/unsupported.html:15`: object creation with `new` is not supported (never); compute the value in Java
- `misc/unsupported.html:16`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or move data to `data-*` attributes
- `misc/unsupported.html:17`: `/*[[${user.name}]]*/ 'x'` in a script was rewritten to `[[${user.name}]]`: Thymeleaf's natural template wrote the value in place of the comment and dropped the default `'x'`, and `[[...]]` inside a comment is a build error in Tessera
- `misc/unsupported.html:19`: `[(...)]` prints `user.bio` without escaping; `t:utext` takes a `SafeHtml`. For stored HTML write `t:utext="html.sanitize(user.bio)"` (`html.sanitizeRich` for editor or Markdown output, or an application `HtmlPolicy` in a `@TemplateFunctions` namespace; guide section 4.10, Rich text), never a recursive fragment over a tree parsed in Java; for markup built in Java return a `SafeHtml`; for plain text use `t:text`

## owners/createOrUpdateOwnerForm.html

- `owners/createOrUpdateOwnerForm.html:1`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/createOrUpdateOwnerForm.html:4`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide section 4.8, Layouts)

## owners/ownerDetails.html

- `owners/ownerDetails.html:3`: `message` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownerDetails.html:3`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownerDetails.html:5`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide section 4.8, Layouts)
- `owners/ownerDetails.html:24`: link `@{{id}/edit(id=owner.id)}` fails the render if a path variable is `null` (`id` = `owner.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide section 3, step 4)
- `owners/ownerDetails.html:25`: link `@{{id}/pets/new(id=owner.id)}` fails the render if a path variable is `null` (`id` = `owner.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide section 3, step 4)
- `owners/ownerDetails.html:49`: link `@{{ownerId}/pets/{petId}/edit(ownerId=owner.id,petId=pet.id)}` fails the render if a path variable is `null` (`ownerId` = `owner.id`, `petId` = `pet.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide section 3, step 4)
- `owners/ownerDetails.html:50`: link `@{{ownerId}/pets/{petId}/visits/new(ownerId=owner.id,petId=pet.id)}` fails the render if a path variable is `null` (`ownerId` = `owner.id`, `petId` = `pet.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide section 3, step 4)

## owners/ownersList.html

- `owners/ownersList.html:3`: `listOwners` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:3`: `totalPages` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:3`: `currentPage` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:6`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide section 4.8, Layouts)
- `owners/ownersList.html:23`: `__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field

## welcome.html

- `welcome.html:5`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide section 4.8, Layouts)
