# thwart-migrate report

Converted 6 template(s); 2 item(s) need manual attention.

## error.html

- `error.html:5`: `<body class="error-page">` is passed to `fragments/layout :: layout`, which inserts it inside its own `<body>`: a second `<body>` is a parse error, which the build rejects. Not rewritten, because `t:remove="tag"` would drop the attributes a browser merges into the layout's `<body>`. Fix: give `fragments/layout :: layout` a `String` parameter for `class="error-page"` and set it on its `<body>` (for example `String bodyClass` and `<body t:class="bodyClass">`), and pass `'error-page'` from this page and `null` from the layout's other calls, then mark this `<body>` `t:remove="tag"` (guide section 4.8, Layouts)

## welcome.html

- `welcome.html:5`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide section 4.8, Layouts)
