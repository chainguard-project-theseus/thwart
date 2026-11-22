# thwart-migrate report

Converted 9 template(s); 52 item(s) need manual attention.

## fragments/inputField.html

- `fragments/inputField.html:3`: fragment `input` parameters declared as `Object` (`label`, `name`, `type`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`); every call passes every argument, so pass `null` where a Thymeleaf call omitted one; a fragment sees only its parameters, so add one for each caller variable it reads (guide templates.md, Fragments and layouts)
- `fragments/inputField.html:10`: dynamic form-field path `*{__...__}` is not supported (preprocessing); pass the path to a fragment's `Field` parameter (guide templates.md, Reusable form fields)
- `fragments/inputField.html:11`: dynamic form-field path `*{__...__}` is not supported (preprocessing); pass the path to a fragment's `Field` parameter (guide templates.md, Reusable form fields)
- `fragments/inputField.html:16`: dynamic form-field path `*{__...__}` is not supported (preprocessing); pass the path to a fragment's `Field` parameter (guide templates.md, Reusable form fields)

## fragments/layout.html

- `fragments/layout.html:2`: fragment `layout` parameters declared as `Object` (`template`, `menu`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`); every call passes every argument, so pass `null` where a Thymeleaf call omitted one; a fragment sees only its parameters, so add one for each caller variable it reads (guide templates.md, Fragments and layouts)
- `fragments/layout.html:34`: Thymeleaf also renders fragment `menuItem` where it is declared, reading its parameters, like any variable, from the scope around it (usually unset there, so `null`), and Thwart doesn't: added `~{::menuItem(null, null, null, null, null)}` before it to keep that copy. For each parameter that has a value there, including one you add for a variable of the enclosing fragment, pass that value in this call instead of `null`; delete the call if the page shouldn't show the copy (guide templates.md, Fragments and layouts)
- `fragments/layout.html:34`: fragment `menuItem` parameters declared as `Object` (`link`, `active`, `title`, `glyph`, `text`); replace each with its type (`Fragment` for markup arguments such as `~{::body}`); every call passes every argument, so pass `null` where a Thymeleaf call omitted one; a fragment sees only its parameters, so add one for each caller variable it reads (guide templates.md, Fragments and layouts)
- `fragments/layout.html:35`: `__...__` preprocessing is not supported (never); inline the dynamic part, e.g. one element per field; for a whole link `@{__${link}__}`, pass the link itself (`@{/owners}` from the caller) and write `t:href="link"`

## misc/fixits.html

- `misc/fixits.html:2`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:2`: `swap` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:2`: `selector` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:2`: `callback` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/fixits.html:10`: no space before attribute `class` (a parse error, which the build rejects): a space was added, which a browser reads the same
- `misc/fixits.html:13`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or put data in a `<script type="application/json">` block
- `misc/fixits.html:15`: `/*[[${owner.id}]]*/ 0` in a script was rewritten to `[[${owner.id}]]`: Thymeleaf's natural template wrote the value in place of the comment and dropped the default `0`, and `[[...]]` inside a comment is a build error in Thwart

## misc/scripts.html

- `misc/scripts.html:2`: `user` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/scripts.html:2`: `page` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/scripts.html:5`: `"[[${user.name}]]"` in a script was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:6`: `'[[${user.id}]]'` in a script was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:11`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or put data in a `<script type="application/json">` block
- `misc/scripts.html:14`: `"[[${page.number}]]"` in a script was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:16`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or put data in a `<script type="application/json">` block
- `misc/scripts.html:19`: `"[[${page.title}]]"` in a script was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)
- `misc/scripts.html:20`: `"[[${user.name}]]"` in a script was unquoted: in Thwart `[[...]]` writes a JSON value with its own quotes; a string renders the same, but a non-string value now renders as JSON (`42`, `true`, `null`, an array or object), not a quoted string: check `== "true"`, `.length` and string concatenation on it (`[[|${x}|]]` keeps a string for a non-null value)

## misc/unsupported.html

- `misc/unsupported.html:2`: `user` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/unsupported.html:2`: `color` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `misc/unsupported.html:3`: `layout:decorate` belongs to the `layout:` dialect, which Thwart does not support; port it by hand
- `misc/unsupported.html:5`: `T(...)` static class access is not supported (never); compute the value in the controller, add it to the model and declare it in `t:params`, or call a static `@TemplateFunction`
- `misc/unsupported.html:6`: Spring bean references (`@bean`) are not supported (never); call the bean in the controller (or in a `@ModelAttribute` method of a `@ControllerAdvice` for a value every page needs), add the result to the model and declare it in `t:params`
- `misc/unsupported.html:7`: `#ctx.request` reads Thymeleaf's evaluation context, which Thwart does not have; a template sees only its `t:params`: read the parameter by name, and add any value the controller does not provide to the model and to `t:params` (guide templates.md, The model)
- `misc/unsupported.html:10`: collection selection/projection is not supported; compute the collection in Java
- `misc/unsupported.html:12`: rewritten: `sec:authorize` to `t:if="auth.isAuthenticated()"`; `auth` ignores a `RoleHierarchy`, a `GrantedAuthorityDefaults` prefix and a `SecurityContextHolderStrategy` bean, so check the gate if the app sets any (guide migrate.md, Spring Security tags)
- `misc/unsupported.html:13`: `th:attr` is not supported; write one `t:<name>` per attribute: `th:attr="title=${a}, href=${b}"` becomes `t:title="a" t:href="b"`
- `misc/unsupported.html:14`: `data-th-text` (HTML5 data-th-* syntax) is not converted; rewrite it as `t:text`
- `misc/unsupported.html:15`: object creation with `new` is not supported (never); compute the value in Java
- `misc/unsupported.html:16`: `th:inline` is not supported; use `[[...]]` in `<script>` (JSON-escaped) or put data in a `<script type="application/json">` block
- `misc/unsupported.html:17`: `/*[[${user.name}]]*/ 'x'` in a script was rewritten to `[[${user.name}]]`: Thymeleaf's natural template wrote the value in place of the comment and dropped the default `'x'`, and `[[...]]` inside a comment is a build error in Thwart

## owners/createOrUpdateOwnerForm.html

- `owners/createOrUpdateOwnerForm.html:1`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/createOrUpdateOwnerForm.html:4`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide templates.md, Layouts)

## owners/ownerDetails.html

- `owners/ownerDetails.html:3`: `message` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownerDetails.html:3`: `owner` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownerDetails.html:5`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide templates.md, Layouts)
- `owners/ownerDetails.html:24`: link `@{{id}/edit(id=owner.id)}` fails the render if a path variable is `null` (`id` = `owner.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide migrate.md, The tools and the loop, step 4)
- `owners/ownerDetails.html:25`: link `@{{id}/pets/new(id=owner.id)}` fails the render if a path variable is `null` (`id` = `owner.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide migrate.md, The tools and the loop, step 4)
- `owners/ownerDetails.html:49`: link `@{{ownerId}/pets/{petId}/edit(ownerId=owner.id,petId=pet.id)}` fails the render if a path variable is `null` (`ownerId` = `owner.id`, `petId` = `pet.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide migrate.md, The tools and the loop, step 4)
- `owners/ownerDetails.html:50`: link `@{{ownerId}/pets/{petId}/visits/new(ownerId=owner.id,petId=pet.id)}` fails the render if a path variable is `null` (`ownerId` = `owner.id`, `petId` = `pet.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide migrate.md, The tools and the loop, step 4)

## owners/ownersList.html

- `owners/ownersList.html:3`: `listOwners` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:3`: `totalPages` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:3`: `currentPage` is declared as `Object` in the generated `t:params` stub; replace it with its view type
- `owners/ownersList.html:6`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide templates.md, Layouts)
- `owners/ownersList.html:23`: link `@{/owners/{ownerId}(ownerId=owner.id)}` fails the render if a path variable is `null` (`ownerId` = `owner.id`), where Thymeleaf wrote an empty segment (`/owners//edit`); if it can be `null`, guard the link with `t:if` (a default of `''` is no fix, since an empty value is rejected too; guide migrate.md, The tools and the loop, step 4)

## welcome.html

- `welcome.html:5`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide templates.md, Layouts)
