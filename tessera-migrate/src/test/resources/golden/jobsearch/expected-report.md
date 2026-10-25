# tessera-migrate report

Converted 6 template(s); 7 item(s) need manual attention.

## fragments/navbar.html

- `fragments/navbar.html:9`: rewritten: fragment `fragments/navbar :: navbar` wrote a whole document (`<html>`, `<head>`, `<body>`) into page bodies; a browser ignored those tags, and they are a parse error, which the build rejects. Deleted its `<html>` and `<body>` tags and its `<head>`, which held `<meta charset="UTF-8">`, `<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">`, `<title>`: put what the pages need in the layout's `<head>`

## groups/groups.html

- `groups/groups.html:8`: rewritten: deleted the tags of a `<body>` inside a `<body>`; a browser ignored them, and a second `<body>` is a parse error, which the build rejects (the page's `<body>` is the layout's or its own)

## resumes/resumes.html

- `resumes/resumes.html:3`: `<link>` is written before the layout `fragments/layout :: layout` writes its doctype, so a browser ignores the doctype and renders the page in quirks mode (a parse error, which the build rejects): move it into the `<head>` the page passes to the layout (`<head t:remove="tag">` passed as `~{::head}`)

## users/companies.html

- `users/companies.html:9`: `<body class="bg-light">` is inside a `<body>`: a browser ignores the tag and merges its attributes into the page's `<body>`, and a second `<body>` is a parse error, which the build rejects. Not rewritten, because deleting it would drop the attributes: move them to the `<body>` the page renders (in a layout, a `String` parameter for a class, say), then delete this `<body>` and its `</body>`

## users/update_profile.html

- `users/update_profile.html:6`: rewritten: deleted the tags of a `<html>` inside a `<body>`; a browser ignored them, and a second `<html>` is a parse error, which the build rejects (the page's `<html>` is the layout's or its own)
- `users/update_profile.html:7`: `<head>` inside a `<body>`: a browser ignores its tags and keeps its elements in the body (a parse error, which the build rejects): move the elements into the `<head>` the page passes to the layout (`<head t:remove="tag">` passed as `~{::head}`), and drop those the layout's `<head>` already has
- `users/update_profile.html:11`: rewritten: deleted the tags of a `<body>` inside a `<body>`; a browser ignored them, and a second `<body>` is a parse error, which the build rejects (the page's `<body>` is the layout's or its own)
