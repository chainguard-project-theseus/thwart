# tessera-migrate report

Converted 6 template(s); 7 item(s) need manual attention.

## fragments/navbar.html

- `fragments/navbar.html:9`: rewritten: fragment `fragments/navbar :: navbar` wrote a whole document (`<html>`, `<head>`, `<body>`) into page bodies; a browser ignored those tags, and they are a parse error, which the build rejects. Deleted those tags and kept their content where a browser put it, in the page body, including what the `<head>` held (`<meta charset="UTF-8">`, `<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">`, `<title>`): delete those the layout's `<head>` already has and move the rest there

## groups/groups.html

- `groups/groups.html:8`: rewritten: deleted the tags of a `<body>` inside a `<body>`; a browser ignored them, and a second `<body>` is a parse error, which the build rejects (the page's `<body>` is the layout's or its own)

## resumes/resumes.html

- `resumes/resumes.html:3`: `<link>` is written before the layout `fragments/layout :: layout` writes its doctype, so a browser ignores the doctype and renders the page in quirks mode (a parse error, which the build rejects). Fix: move it into a `<head t:remove="tag">` in this page (beside its `<body>`) and pass `~{::head}` as the `head` argument of `fragments/layout :: layout`

## users/companies.html

- `users/companies.html:9`: `<body class="bg-light">` is inside a `<body>`: a browser ignores the tag and merges its attributes into the page's `<body>`, and a second `<body>` is a parse error, which the build rejects. Not rewritten, because deleting it would drop them. Fix: give `fragments/layout :: layout` a `String` parameter for `class="bg-light"` and set it on its `<body>` (for example `String bodyClass` and `<body t:class="bodyClass">`), and pass `'bg-light'` from this page and `null` from the layout's other calls, then delete this `<body>` and its `</body>` (guide section 4.8, Layouts)

## users/update_profile.html

- `users/update_profile.html:6`: rewritten: deleted the tags of a `<html>` inside a `<body>`; a browser ignored them, and a second `<html>` is a parse error, which the build rejects (the page's `<html>` is the layout's or its own)
- `users/update_profile.html:7`: `<head>` inside a `<body>`: a browser ignores its tags and keeps its elements in the body (a parse error, which the build rejects). Fix: delete its `<head>` and `</head>` and the elements the page's `<head>` already has; for each of the rest, move it into a `<head t:remove="tag">` in this page (beside its `<body>`) and pass `~{::head}` as the `head` argument of `fragments/layout :: layout`
- `users/update_profile.html:11`: rewritten: deleted the tags of a `<body>` inside a `<body>`; a browser ignored them, and a second `<body>` is a parse error, which the build rejects (the page's `<body>` is the layout's or its own)
