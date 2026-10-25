# tessera-migrate report

Converted 6 template(s); 2 item(s) need manual attention.

## error.html

- `error.html:5`: `<body class="error-page">` is passed to `fragments/layout :: layout`, which inserts it inside its own `<body>`: a second `<body>` is a parse error, which the build rejects. Not rewritten, because `t:remove="tag"` would drop its attributes (a browser merges them into the layout's `<body>`): move them to the layout's element (a `String` parameter for a class, say), then mark this element `t:remove="tag"` (guide section 4.8, Layouts)

## welcome.html

- `welcome.html:5`: rewritten: added `t:remove="tag"` to the `<body>` this page passes to `fragments/layout :: layout`, which inserts it inside its own `<body>`, so only its content is passed (a second one is a parse error, which the build rejects; guide section 4.8, Layouts)
