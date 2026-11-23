# Writing Thwart templates

The model, the template syntax and the output contexts: what you need to write or edit the
templates of an application that uses Thwart. The build is in [setup.md](setup.md), moving views
from another engine in [migrate.md](migrate.md), and known limitations in
[limitations.md](limitations.md).

The compiler's error messages are the detailed documentation for each rule: they name the file,
line and column, say what is wrong, and name the fix. Examples marked `<!-- compile: ... -->` in the
source of the guide's files are compiled and rendered by Thwart's tests, so they are known to work.

## The model

Thwart templates are HTML files with `t:` attributes, close to Thymeleaf's `th:` syntax. An
annotation processor compiles them to Java classes during the application's normal `javac`, so a
template error is a build error. At runtime nothing parses or reads templates.

**A template sees only its typed parameters.** The first element declares them
(`t:params="Owner owner, List<Pet> pets"`). With Spring each is read from the model attribute of
the same name, or else from the handler's `@PathVariable` of that name. A path variable has the
handler's converted type (`int ownerId` arrives as an `Integer`), and only those the handler method
declares are there: one that is only in the URL pattern is `null`. There is no request,
session, application or bean scope, no request parameters, cookies or headers. Engines you are
leaving often fall back to those scopes when a name is not in the model; Thwart doesn't, so **a
missing model attribute is `null`**, which prints nothing, and reading a property of it fails the
render with a `TemplateModelException` naming the `null` attributes, its cause the
`NullPointerException` (write `a?.b` where `null` is expected). Generic element types aren't
checked: a `List<String>` passed for `List<Owner>` fails with a `ClassCastException` where an
element is read. Before converting a
template, find everything it reads that the controller doesn't put in the model, and make the
controller provide it. Values every page needs (the current user, the request URI) come from
`@ModelAttribute` methods in a `@ControllerAdvice`.

**Templates read data; they don't run code.** A template reads record components and
`getX()`/`isX()` getters of view-safe types ([View types](#view-types)), plus built-ins such as `size()`. It
cannot call other methods, construct objects or reach static members. Logic lives in Java: a
getter, a record the controller builds, or a static `@TemplateFunction` ([Functions](#functions)).

**The output context picks the escaper.** Text, attribute values, URLs and `<script>` values are
each escaped for where they appear, with no switch to turn it off ([Output contexts](#output-contexts)). Raw markup must be
a `SafeHtml` built in Java, and stored HTML goes through `html.sanitize(...)`. Values the old
engine printed raw now come out escaped; that is intended, not a difference to undo.

**Forms bind through Spring's `BindingResult`**, as Spring's form tags and macros do ([Forms](#forms)).

**Fragments see only their parameters.** A fragment is a typed function that cannot see the
caller's variables. Includes, macros with bodies, tag files, partials and layouts all become
fragment calls, with markup passed as a `Fragment` ([Fragments and layouts](#fragments-and-layouts)).

**Names are literals.** View names, fragment references, message keys and link paths are checked
at build time. Choose between literals with a conditional.

**Values print with `String.valueOf`**, with no locale-aware formatting: use `numbers.*`,
`temporals.*` or your own function. `null` text renders empty and a `null` attribute is omitted, but
inside `|...|` and `+` a `null` prints `null`.

| If the old template... | Do this instead |
|---|---|
| reads the request, session, application scope, cookies or headers, or falls back to them | Put the value in the model and declare it in `t:params`. |
| calls a method with arguments, a static method, a bean or a constructor | A getter, a record built by the controller, or a `@TemplateFunction`. |
| filters, sorts, groups or projects a collection | Do it in the controller. |
| assigns a variable | `t:with="x=..."`: an immutable local for one element. |
| builds a view name, fragment name, message key or link path from strings | Conditionals between literals; link `{placeholders}`; a `Fragment` argument. A message key computed from data is resolved in Java and passed as a string. |
| evaluates a string as a template (`?eval`, `#evaluate`, `__${...}__`) | Compute the value in Java. |
| prints a string unescaped | Stored rich text: `t:utext="html.sanitize(article.text)"`. Markup built in Java: a `SafeHtml`. A bundle message with markup: `t:utext="#{key}"` ([Messages and links](#messages-and-links)). |
| puts expressions in `style`, a `data-*` or a framework attribute (`th:hx-get`) | [Output contexts](#output-contexts): computed attributes are a closed list, each with a fixed context. |
| has an event handler (`onclick="..."`, any `on*`, static or computed) or a `javascript:` URL | Build errors: a strict CSP blocks inline script. Mark the element (`data-action="save"`) and attach one listener from a `<script>`: `document.addEventListener('click', e => { const el = e.target.closest('[data-action=save]'); if (el) save(el); });`. A `href="javascript:void(0)"` link becomes a `<button type="button">`, or `href="#"` with `e.preventDefault()` in the listener. htmx's `hx-on` needs `eval`, which the CSP blocks too: listen for the `htmx:` event (`htmx:afterRequest`) from a script. |
| includes a file, calls a macro or tag with a body, or extends a layout | A fragment call with `Fragment` arguments ([Fragments and layouts](#fragments-and-layouts)). |
| binds a form through form tags or macros | `t:object`, `t:field`, `t:errors`, `fields.*` ([Forms](#forms)). |
| reads an `Exception`, a `Date` or another non-view-safe value | Put what the page shows (a message `String`, a `LocalDate`, an `Instant`) in the model. |
| prints a whole object (`th:text="${pet.type}"`, `'x' + ${owner}`), relying on its `toString()` | A build error for a record or `@TemplateView` class (or a list of them): print the property the page shows, with `?.` if the value can be null (`t:text="pet.type?.name"`); for a list or `strings.join(owner.pets, ', ')`, print each item in a loop (`<t:block t:each="pet : owner.pets" t:text="petStat.last ? pet.name : pet.name + ', '"></t:block>`); or format it with a `@TemplateFunction` ([Functions](#functions)). |

## Templates and parameters

A template is an HTML file. Its view name is its path under the templates root without `.html`
(`owners/list.html` is `owners/list`). The first element carries the header:

<!-- compile: template guide/hello.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev"
      t:import="com.example.guide.*"
      t:params="Customer customer, List<Order> orders, boolean admin">
  <body>
    <h1 t:text="|Hello, ${customer.name}!|">Hello, Ann!</h1>
    <p t:if="orders.isEmpty()">No orders yet.</p>
    <ul t:unless="orders.isEmpty()">
      <li t:each="o, stat : orders" t:classappend="stat.odd ? 'odd'">
        [[${stat.count}]]. <span t:text="o.total">9.99</span>
      </li>
    </ul>
    <a t:if="admin" t:href="@{/admin/customers/{id}(id=customer.id)}">Edit</a>
  </body>
</html>
```

<!-- compile: java -->
```java
package com.example.guide;

public record Customer(long id, String name) {}
```

<!-- compile: java -->
```java
package com.example.guide;

import java.math.BigDecimal;

public record Order(String number, BigDecimal total) {}
```

- `t:params` is `Type name, ...` with Java types (generics, arrays, `Outer.Inner`), without
  wildcards: write `List<Owner>`, not `List<? extends Owner>`. A primitive parameter must be
  present in the model. `t:import` lists classes or packages, comma-separated in the one
  attribute (`t:import="com.example.Owner, com.example.pets.*"`); `java.lang`, `java.util`,
  `java.time`, `java.math` and `dev.chainguard.thwart.runtime` are always imported.
- The template compiles to `<thwart.package>.guide.HelloTemplate` with
  `of(Customer, List<Order>, boolean)`.
- Static attribute values and text are prototypes that `t:` attributes replace. `<t:block>`
  renders only its content. HTML comments are written to the page; the template comment
  `<!--/* ... */-->` is dropped with everything inside it. Use it for other engines'
  template-only comments.

## View types

Templates may read only **view-safe** types, checked at build time for every step of a property
chain: primitives and their boxes, `String`, enums, records, `java.time` values, `BigDecimal`,
`BigInteger`, `UUID`, `Optional`, `java.util` collections, maps and arrays of view-safe types, `SafeHtml`,
and public classes annotated `@TemplateView`. Declare collection getters as the
`java.util` interface: `java.util.concurrent`, Guava and your own collection subclasses aren't
view-safe. A view type, any class it is nested in, and its type arguments must be public. `Object`,
`Class`, reflection, `System`, servlet types and Spring context types are never view-safe.

On a view-safe type a template can read record components (`owner.name()`), public no-argument
getters (`owner.name`, `owner.getName()`, `isX()` for booleans; `new` is a keyword, so write
`pet.isNew()`), `size()`/`isEmpty()`/`first`/`last` on collections, `length` on arrays, and index
access (`list[0]`, `map['key']`). Nothing else. So `map.key` is a build error (write
`map['key']`), and an `Optional` offers only `present`: unwrap it in the view type (`orElse(null)`).
An enum's `name()` can't be called either: print the enum itself (its `toString()`), or add a
getter where `toString()` is overridden.

**Recommended: a view record per page**, built by the controller from its entities. Lazy loading
then happens in the controller, the template sees only what the record lists, and shaping is plain
Java. On Java 8, write it as a small class with getters annotated `@TemplateView`.

**Shortcut: `@TemplateView` on existing classes.** For a large port it can be quicker to annotate
the entities the templates already read. Then every public getter is readable, lazy associations
load during rendering, and every class reached through a getter needs the annotation too. Getters
inherited from an unannotated superclass resolve.

<!-- compile: java -->
```java
package com.example.guide;

import dev.chainguard.thwart.runtime.TemplateView;
import java.util.List;

@TemplateView
public class Account {
  private final String email;
  private final List<String> roles;

  public Account(String email, List<String> roles) { this.email = email; this.roles = roles; }

  public String getEmail() { return email; }
  public List<String> getRoles() { return roles; }
}
```

## Expressions and attributes

Expressions and attributes are Thymeleaf's Standard dialect with `t:` for `th:`, minus everything
that runs code. What differs:

- `${...}` is optional (`t:text="owner.name"`). `*{...}` works only inside `t:object`, for one
  property path: `*{firstName} + ' ' + *{lastName}`. Utility objects are function namespaces
  ([Functions](#functions)); `#ctx`, `#request`, `#dates` and the rest don't exist.
- Not supported: `T(...)`, `new`, `@bean`, `__...__` preprocessing, assignment, lambdas,
  selection and projection (`.?[...]`, `.![...]`), `div`/`mod` (use `/`, `%`).
- `==` is `Objects.equals`; an enum compared with a string literal compares the constant's name.
  Comparing types that never share a value (a `String` variable with an enum or a number) is a
  build error: compare the enum with a literal (`status == 'ACTIVE'`), or give both sides one type in Java.
  `c ? a` without else is `null`. Both branches of `?:` and `c ? a : b` need compatible types
  (an `Integer` and a `Long` don't mix: give the view type one number type).
  `x ?: d` evaluates `d` only when the variable `x` is null; `a.b ?: d` evaluates `d` either way,
  so when `d` could fail or is costly, name the value first: `t:with="b=${a.b}"`, then `b ?: d`.
- `<`, `>`, `<=`, `>=`, `-` and arithmetic are Java's, so a `null` number (an `Integer`
  property) fails the render. Using an `a?.b` there is a build error: write `(u?.age ?: 0) > 18`
  or `u != null and u.age > 18`. `list.first` and `list.last` are `null` for an empty list.
  A `BigDecimal` compares with any number (`price > 100`), but arithmetic on one (`price * 2`)
  is a build error: compute it in the view type or a `@TemplateFunction`.
- `|...|` and `+` are Java string concatenation, so a `null` prints `null`. To print nothing,
  write `x ?: ''` for a `String` and `x == null ? '' : '' + x` otherwise.
- `t:if` is false for `null`, `false`, `0` and the strings `"false"`, `"off"` and `"no"`, and true
  for everything else, including `""` and empty collections. Write what the old condition meant:
  `x != null`, `!list.isEmpty()`.
- Attributes on one element run in Thymeleaf's order: `t:fragment` → `t:insert`/`t:replace` →
  `t:each` → `t:if`/`t:unless`/`t:switch`/`t:case` → `t:object`/`t:with` → other attributes →
  `t:text`/`t:utext`/`t:errors` → `t:remove`. So a `t:if` doesn't guard a `t:replace` on the same
  element: put it on an enclosing element or a `<t:block>`.
- `t:each` iterates an `Iterable`, array or `Map` (`e.key`, `e.value`), with status `index`,
  `count`, `size`, `first`, `last`, `even`, `odd` (implicit name `xStat`). `t:with` locals are
  immutable. `t:remove` takes `all` or `tag` (other values: the table in [limitations.md](limitations.md)).
- Any `t:<name>` sets that attribute, and `null` omits it, where Thymeleaf's `th:value`,
  `th:href`, `th:src`, `th:action`, `th:name` and `th:type` wrote it empty. Where the empty
  attribute matters, write `x ?: ''`. Boolean attributes (`t:checked`, ...) take a condition.
- Not supported: `th:attrappend`/`attrprepend`, `th:alt-title`, `th:lang-xmllang`, `th:assert`,
  `th:include` (use `t:insert`), `th:inline`; `th:attr` and `th:errorclass` (the table in [limitations.md](limitations.md)).
- A leftover `th:*` (or `data-th-*`), `sec:*` (or `data-sec-*`) or `layout:*` attribute, or any prefixed name
  whose prefix has no `-` other than `xml:`, `xmlns:` and `xlink:`, is a build error, not text to
  copy: write the `t:` attribute it stands for (`thwart-migrate` rewrites `th:` ones; `sec:` is
  in [migrate.md](migrate.md#notes-for-specific-engines), layouts in
  [Fragments and layouts](#fragments-and-layouts)), or remove it.

## Output contexts

The compiler decides the context from the element and attribute name; you can't override it. A
computed attribute must be on one closed list: `data-*`, `aria-*`, and the names in the table
below. Any other computed name is a build error (recipes below the table). What each context
accepts:

| Context | Where | Accepts |
|---|---|---|
| Text | element text, `t:text`, `[[...]]` | Any value but a record or `@TemplateView` object ([The model](#the-model)), escaped. |
| Raw HTML | `t:utext`, `[(...)]` | A `SafeHtml`, or a bundle message, sanitized by the message policy ([Messages and links](#messages-and-links)). |
| Attribute | text: `abbr alt download id label name pattern placeholder title value`; keywords: `autocomplete dir enterkeyhint formmethod hidden inputmode method scope spellcheck translate`; numbers: `cols colspan datetime height high low max maxlength min minlength optimum rows rowspan size span start step tabindex width`; booleans (they take a condition): `async autofocus checked defer disabled formnovalidate multiple novalidate open readonly required reversed selected`; `for form headers list accept class hreflang lang role`; the WAI-ARIA 1.2 `aria-*` names; `content` of a descriptive `<meta>` (`description`, `og:title`, ...: below) | Any value but a record or `@TemplateView` object, escaped. |
| Typed attribute | `target`; `value` other than on `button data input li meter option progress select`; `for` other than on `label` and `output` | Literals, numbers, and a choice between literals (`${newWindow} ? '_blank' : '_self'`). |
| URL | `href`, `src`, `action`, `formaction`, `cite`, `poster`, on the elements HTML defines them for (`<a href>`, `<img src>`, `<form action>`, `<video poster>`, ...). On any other element, `<portal>`, a custom element or one with a static `is=` included, they are a build error: make a link an `<a t:href>` (add `role="button"` to style it as a button); `t:data-*` takes only a neutral value (a literal-path link, a number, a boolean). Also `content` of a `<meta>` keyed `og:image`, `og:url` or `twitter:image` (below) | Any value; only `http`, `https`, `mailto`, `tel` and relative URLs pass, anything else becomes `about:invalid#thwart`. Forms: see [Forms](#forms). |
| Resource URL | `script[src]`, `iframe[src]`, `frame[src]`, `embed[src]`, `object[data]`, `link[href]`, `base[href]` | A same-origin `@{...}` link without query parameters, such as `@{/js/{v}/app.js(v=${v})}`. Load another origin with a static attribute. |
| Neutral | `data-*`, and an `aria-*` name outside WAI-ARIA 1.2 | A framework-neutral value (below). |
| Script | `<script>` text | `[[...]]` writes JSON (below). |
| Style | the `style` attribute | Literals, numbers and `CssValue`s (`css.color(c)`, `css.length(l)`, `css.url(u)`, `css.var(name)`), combined with `+`, `\|...\|` and conditionals. No expressions in `<style>`. |
| Refresh | `content` of `<meta http-equiv="refresh">` | `'3;url=' + @{/path...}`: a literal delay and a same-origin link. |
| Forbidden | `on*`, `srcdoc`, URL lists (`srcset`, `imagesrcset`, `ping`), and what the parser reads: `type` on `<script>` and `<input>`, `<meta http-equiv>` and `charset`, `<meta name>` (it decides what `content` means), `<template shadowroot*>` and `for`, `encoding`, `is`, `xmlns*`, `<font color/face/size>`, names outside `[A-Za-z_][A-Za-z0-9_.:-]*` (Vue's `:x`, `@x`); and every name not on the list above: `content` other than a refresh or a descriptive `<meta>`, `type`, `rel`, `loading`, `nonce`, `property`, `hx-*`, `x-*`, `v-*`, a custom element's own attributes, ... | Nothing computed. |

A computed `id`, `name` or `form` takes any value, so it can clobber page globals (`<form
name="cookie">` shadows `document.cookie`) or move a control into another form. Compute them from
your own keys or a prefixed id, not raw user input.

**Descriptive `<meta>`.** Search results and link previews read a page's `<meta>` tags. A computed
`content` is allowed on a `<meta>` with exactly one static `name` or `property` from this list,
and no `http-equiv`, `charset` or `itemprop`:

- text, escaped: `description`, `keywords`, `author`, `og:title`, `og:description`,
  `og:site_name`, `og:image:alt`, `twitter:title`, `twitter:description`, `twitter:image:alt`;
- one URL, checked like an `<a href>`: `og:image`, `og:url`, `twitter:image`.

The key is matched as written, folding A-Z only: `name="Description"` is on the list,
`name=" description"` is not. Any other `<meta>` content (`referrer`, `viewport`, `robots`,
`htmx-config`, a site-verification token, `twitter:player`, ...) is a build error, because a
browser, crawler or script may act on it: write it static, or one `<meta>` per case under `t:if`.

<!-- compile: template guide/meta.html -->
```html
<html xmlns:t="https://thwart.chainguard.dev" t:params="int year, String pageUrl, String imageUrl">
  <head>
    <title t:text="|Talks of ${year}|">Talks</title>
    <meta name="description" t:content="|Every talk of the ${year} conference|">
    <meta property="og:url" t:content="pageUrl">
    <meta property="og:image" t:content="imageUrl">
  </head>
  <body></body>
</html>
```

Crawlers want absolute URLs in `og:image`, `og:url` and `twitter:image`: pass a full `https://`
URL from the model, not a relative `@{/...}` link. Build `og:url` from your configured base URL
and the route, never from the request's `Host` header or query string, or a forged request can
choose the URL that shares of the page count toward.

**A computed name that is not on the list.** Port it by what it is for:

| The attribute is | Recipe |
|---|---|
| read by your script or a client library (`th:hx-get`, `th:attr="blockid=${id}"`, `th:icon`) | A number, boolean or link in a `data-*` attribute: `t:data-hx-get="@{/items/{id}(id=${id})}"` (htmx reads `data-hx-*` too), `t:data-blockid="${id}"`; the script reads `el.dataset`. A string goes in a script declaration or a JSON block (Scripts, below). |
| one of a few values (`th:type="${submit} ? 'submit' : 'button'"`, `th:rel`, `th:loading`, `<input type>`) | A static attribute, or one element per value under `t:if`/`t:switch` (the `fields` example in [Fragments and layouts](#fragments-and-layouts)). |
| `th:attr="nonce=${nonce}"` on a `<script>` | Delete it: Thwart stamps the CSP nonce itself ([Rendering](#rendering)). |
| `content` of a `<meta>` whose key is not descriptive (`referrer`, `viewport`, `htmx-config`, ...) | A static `content`, or one `<meta>` per case under `t:if`. Other page data for JavaScript goes in a script declaration (Scripts, below). A descriptive key (`description`, Open Graph, Twitter cards) takes `t:content` directly (above). |
| `<meta name="_csrf" th:content="${_csrf.token}">` and `<meta name="_csrf_header" th:content="${_csrf.headerName}">` | `<t:csrf-meta/>` writes both ([Forms](#forms)). |
| `srcset`, `<font color>` | One `src` (or a static `srcset`); a `<span>` with a `t:class`. |

SVG, MathML, CDATA and the obsolete raw-text elements are static or rejected: the table in [limitations.md](limitations.md).

**Scripts.** In a JavaScript `<script>`, each `[[...]]` must be the whole value of a declaration at
the top of the script, before any other code: `const roles = [[${account.roles}]];`. The value is
written as JSON in parentheses, and the code below uses the name. That is how the compiler knows
where every value lands without a JavaScript parser. So a value inside a string (`"[[${x}]]"`), a
comment (Thymeleaf's `/*[[${x}]]*/ 'd'`), a template literal or an expression is an error: move it
into a declaration (`var x = [[${x}]];` at the top, or `const x0 = [[${x}]]; x = x0;`). Records and
`@TemplateView` beans become JSON objects of their readable properties, as Thymeleaf's Jackson
output does, so `var chart = [[${chart}]];` works unchanged; pass the object, not a JSON string
built in Java. Data can also go in a `<script type="application/json" id="data">[[${data}]]</script>`
read with `JSON.parse`; inside it, write `{"name": [[${x}]]}` without quotes around the value.

<!-- compile: template guide/contexts.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Account"
     t:params="Account account, SafeHtml bio, String homepage">
  <a t:href="homepage" t:text="account.email">me@example.com</a>
  <section t:utext="bio">Bio</section>
  <button t:title="account.email" class="js-copy">Copy</button>
  <script>
    const roles = [[${account.roles}]];
  </script>
  <script t:src="@{/js/app.js}"></script>
</div>
```

**Framework-neutral values.** In many client frameworks `data-*` is a code channel (Knockout's
`data-bind`, htmx's `data-hx-on`), and Thwart models none of them. So a computed `data-*` takes only
a number, a boolean, a same-origin link with a literal path and no query, or a choice between those
and literals: `t:data-id="${item.id}"` compiles, `t:data-name="${item.name}"` doesn't. Static
attributes are copied as written. Port a computed string by what it is for:

| The string is | Recipe |
|---|---|
| a request value: `hx-vals`, `hx-headers`, a query in `hx-get` | Hidden inputs and `hx-include` (below), or a link (`t:href`, which takes a query) with `hx-boost`. |
| text for people: a tooltip, a label, a status | `title` or element text. Bootstrap's tooltip reads `title`. |
| `hx-confirm` text | A static `hx-confirm`, and the text in a hidden element your confirm script reads. |
| data your own script reads (`el.dataset.x`) | A JSON block or a hidden input the script reads instead. |
| Alpine `x-data`, Vue props, other component state | A named component (`Alpine.data`, `createApp(App, props)`) that reads a JSON block. |
| options a third-party plugin reads from `data-*` | A number on each element, the strings in a JSON block, and an init script that passes them through the plugin's API (below). If the plugin's only text option is HTML, escape the text first. |
| a key a plugin only compares (a gallery group name) | A neutral value that is equal where the strings were: a number or a link. |
| an id with a prefix (`"hint-" + id`) | The number; the script adds the prefix. |
| a URL held in a variable | A link with a literal path, `@{/items/{id}(id=${id})}`. |

Don't list an open-ended range as literals (`row-0`, `row-1`, ...), hard-code a message per locale,
or copy a JSON value back into a `data-*` attribute for an unchanged plugin. A recipe changes the
page: write down which recipe caused each `compare` difference, and mask a changed asset hash narrowly.

<!-- compile: template guide/recipes/hidden-inputs.html -->
```html
<ul xmlns:t="https://thwart.chainguard.dev" t:params="List<String> skus">
  <li t:each="sku : skus">
    <input type="hidden" name="sku" t:value="sku">
    <button hx-post="/cart" hx-include="previous input">Add</button>
  </li>
</ul>
```

<!-- compile: template guide/recipes/plugin.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev" t:params="List<String> tips">
  <script type="application/json" id="tips-data">[[${tips}]]</script>
  <span t:each="tip, stat : tips" class="hint" t:data-tip="stat.index">?</span>
  <script t:src="@{/js/tips.js}"></script>
</div>
```

```js
// /js/tips.js
const tips = JSON.parse(document.getElementById('tips-data').textContent);
document.querySelectorAll('.hint').forEach((el) => {
  new bootstrap.Tooltip(el, { title: tips[el.dataset.tip] }); // text, not HTML
});
```

If the page re-initializes the plugin after swapping in new HTML, call the same init function on
the swapped element.

**Rich text and `SafeHtml`.** For stored HTML (articles, bios) that the old page printed raw, write
`t:utext="html.sanitize(article.text)"`: it keeps basic formatting and checked links and drops
everything else. `html.sanitizeRich` also keeps same-origin images, `language-*` classes, ids and
`target="_blank"` links, for editor or Markdown output (an `<image>` tag counts as the `<img>` a
browser makes of it). Neither keeps tables, `style`, `iframe` or `svg`. For another mix, build a policy with `HtmlPolicy.builder()` (`allowElements`,
`allowAttributes`, `allowClasses`, `linkRel`, `allowLinkTarget`) and expose it as a function:

<!-- compile: java -->
```java
package com.example.guide;

import dev.chainguard.thwart.runtime.HtmlPolicy;
import dev.chainguard.thwart.runtime.SafeHtml;
import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;

@TemplateFunctions("notices")
public final class NoticeFunctions {
  private static final HtmlPolicy POLICY =
      HtmlPolicy.builder()
          .allowElements("p", "br", "b", "a", "ul", "li")
          .allowClasses("status-*")
          .build();

  private NoticeFunctions() {}

  @TemplateFunction
  public static SafeHtml html(Object text) {
    return SafeHtml.sanitize(text == null ? null : text.toString(), POLICY);
  }
}
```

The builder accepts only names from a closed set, `HtmlPolicy.ELEMENTS` and
`HtmlPolicy.ATTRIBUTES`, and class names or prefixes, never `"*"`. Whatever the policy, images stay
same-origin paths and ids get the prefix `user-content-`. A kept `img` makes the browser send a
same-origin GET with the user's cookies, so don't serve state-changing GETs. Don't parse stored
HTML in Java into records and rebuild it with a self-inserting fragment (`STRUCTURE_DEPENDS_ON_DATA`).

**Inline HTML in a paragraph, heading, link or button.** A `SafeHtml` may hold blocks, so the
compiler rejects it inside an open `<p>`, heading, `<a>` or `<button>` (`RAW_CLOSES_ELEMENT`): a
`<p>` or `<div>` in the value would close the element around it. For a value with inline markup
only (a bio with `<b>`, lines joined with `<br>`), return a `SafeInlineHtml` instead: it is
accepted wherever an HTML message is. Its policy comes from `HtmlPolicy.inlineBuilder()`, which
allows only names from `HtmlPolicy.INLINE_ELEMENTS` (`b i em strong small s u sub sup code kbd
mark abbr cite q dfn time span br wbr img`), so no links and no blocks. Allowing `img` keeps its
`src` without an `allowAttributes` call (`src` isn't in `HtmlPolicy.ATTRIBUTES`), but only a
same-origin path starting with a single `/`: an image whose `src` is a relative path such as
`../images/x.gif`, another host or a `data:` URL is dropped. `alt`, `title`, `width` and `height`
still need `allowAttributes`. Join lines with `concat` and `escape`:

<!-- compile: java -->
```java
package com.example.guide;

import dev.chainguard.thwart.runtime.HtmlPolicy;
import dev.chainguard.thwart.runtime.SafeInlineHtml;
import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;

@TemplateFunctions("inline")
public final class InlineFunctions {
  private static final HtmlPolicy POLICY = HtmlPolicy.inlineBuilder().allowElements("b", "i", "span", "br").build();
  private static final SafeInlineHtml BR = SafeInlineHtml.sanitize("<br>", POLICY);

  private InlineFunctions() {}

  @TemplateFunction
  public static SafeInlineHtml bio(Object text) {
    return SafeInlineHtml.sanitize(text == null ? null : text.toString(), POLICY);
  }

  @TemplateFunction
  public static SafeInlineHtml twoLines(Object first, Object second) {
    return SafeInlineHtml.concat(SafeInlineHtml.escape(String.valueOf(first)), BR, SafeInlineHtml.escape(String.valueOf(second)));
  }
}
```

Then `<p t:utext="inline.bio(speaker.bio)">bio</p>` compiles, and so does an `<a>` or `<h3>` around
it. Content with links or blocks stays a `SafeHtml`, inserted in a `<div>`.

In Java, `SafeHtml.escape`, `SafeHtml.concat` and `SafeHtml.sanitize` build markup from checked
parts. Nothing wraps a string or a template's output unchecked: insert the other template with
`t:insert` instead.

**CSS.** A `style` value is built from literals, numbers and `CssValue`s:
`t:style="|background-color: ${css.color(category.color)}|"`; `css.var('--brand')` writes
`var(--brand)`. `css.color`, `css.length`, `css.url` and `css.var` return `null` for anything they
don't accept. Otherwise use `t:class`/`t:classappend`.

## Messages and links

**Messages.** `#{key}` and `#{key(arg1, arg2)}` render through the application's `MessageSource`
(`{0}` placeholders). The key is a literal; choose with a conditional:
`active ? #{status.active} : #{status.inactive}`. A message is text and is escaped. With
`-Athwart.messages`, a missing key is a build error; without it, it renders `??key_locale??`. A
key computed from data has no template form: resolve it in Java with `MessageSource` and the
request `Locale` and pass the string in the model.

**Messages with markup.** A message whose value holds markup (Thymeleaf `th:utext="#{...}"`, JSP
`<spring:message>` without escaping) prints as HTML with `t:utext="#{key(args)}"`. The arguments
are escaped, and at render time the formatted message is sanitized with a fixed policy: inline
markup (`b i em strong u s sub sup small mark code kbd span br`, a `title`) and links (`a` with an
`http`, `https`, `mailto`, `tel` or relative `href`, given `rel="noopener noreferrer"`;
`target="_blank"` is kept). Anything else is dropped and its text kept: a `<p>` or `<div>`, a
`class` or `style`. A message is inline content, so it fits in a `<p>` or heading; one with a link
can't go inside another link (the build says so), a key whose values have no `<a>` can. For block
markup, keep the text in the bundle and the markup in the template.

<!-- compile: messages -->
```properties
phaseout.notice=This version is being phased out: see <a href="/phaseout">the plan</a>.<br>Save your work.
welcome.html=Hello <b>{0}</b>!
```

<!-- compile: template guide/messages-html.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev" t:params="String name">
  <p class="alert" t:utext="#{phaseout.notice}">notice</p>
  <p t:utext="#{welcome.html(name)}">Hello <b>Ann</b></p>
</div>
```

**Links** are Thymeleaf's: `@{/owners/{id}(id=owner.id, q=term)}` fills `{id}` (percent-encoded)
and turns other parameters into a query string; `@{{id}/edit(id=x)}` is page-relative; absolute
links must be `http`/`https`. A path in quotes is the same constant (`@{'/owners?page=1'}`), but
one built with `+` or `${...}` is a build error: write `@{/owners(page=${i})}`. Links get the
context path and `response.encodeURL`. A `null`, empty, `.` or `..` path variable fails the
render: guard the link with `t:if`. A URL computed in Java goes in `t:href` as a `String`
(scheme-checked); a URL with another scheme (`sms:`, `data:`, ...) must be a static attribute.

## Functions

Templates call functions as `namespace.fn(args)` or `#namespace.fn(args)`. All built-ins are
null-tolerant.

| Namespace | Functions |
|---|---|
| `strings` | `isEmpty`, `isBlank`, `defaultString(s, def)`, `abbreviate(s, max)`, `toUpperCase`, `toLowerCase`, `capitalize`, `trim`, `contains`, `startsWith`, `endsWith`, `substring(s, start[, end])`, `replace(s, before, after)`, `join(iterable, sep)` |
| `numbers` | `formatInteger(n, minDigits[, thousandsPoint])`, `formatDecimal(n, minIntDigits, decimals[, decimalPoint])`, `formatDecimal(n, minIntDigits, thousandsPoint, decimals, decimalPoint)`, `formatPercent(n, minIntDigits, decimals)`, `formatCurrency(n)`, `sequence(from, to)` |
| `temporals` | `format(temporal, pattern)`, `formatIso(temporal)` |
| `lists` | `size`, `isEmpty`, `contains(list, x)` |
| `css` | `color(s)`, `length(s)`, `url(s)`, `var(name)` |
| `html` | `sanitize(s)`, `sanitizeRich(s)` |
| `auth` (Spring starter) | `isAuthenticated()`, `hasRole('ADMIN')`, `hasAnyRole(...)`, `hasAuthority('PERM_X')`, `hasAnyAuthority(...)`, `name()` |
| `fields` (inside `t:object`) | [Forms](#forms) |
| `ids` | `prev(name)`, `next(name)`, `seq(name)`: Thymeleaf's `#ids` ([Forms](#forms)) |

`numbers.*` and `temporals.format` use the render locale, as Thymeleaf's do (a point type is
`'POINT'`, `'COMMA'`, `'WHITESPACE'`, `'DEFAULT'` or `'NONE'`). `numbers.*` rounds half-even, as
Thymeleaf's do, and a tie in a `double` is broken by its exact binary value: `formatDecimal(16.65, 1, 1)`
is `16.6`, because the `double` 16.65 is 16.6499... Round in Java first if a page needs another rule.
`numbers.sequence(1, n)` counts down when `n` is below 1, so guard a loop the old engine ran zero
times. Add your own namespace:

<!-- compile: java -->
```java
package com.example.guide;

import dev.chainguard.thwart.runtime.TemplateFunction;
import dev.chainguard.thwart.runtime.TemplateFunctions;
import java.math.BigDecimal;
import java.math.RoundingMode;

@TemplateFunctions("money")
public final class Money {
  @TemplateFunction
  public static String euros(BigDecimal amount) {
    return amount == null ? "" : "€" + amount.setScale(2, RoundingMode.HALF_UP);
  }
}
```

<!-- compile: template guide/functions.html -->
```html
<p xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Order" t:params="Order order">
  Total: <b t:text="money.euros(order.total)">€9.99</b>
  (<span t:text="#strings.abbreviate(order.number, 8)">ORD-1</span>)
</p>
```

Methods are `public static`, annotated `@TemplateFunction`, in a class compiled in the same `javac`
run as the templates. A leading `java.util.Locale` parameter receives the render locale and is not
a template argument. Functions can't see Spring beans or the request; don't reach them through
static state.

## Forms

`t:object` names the form object, the model attribute Spring binds.

<!-- compile: template guide/signup.html -->
```html
<form xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.*" t:params="Signup signup, List<Plan> plans"
      t:object="signup" t:action="@{/signup}" method="post">
  <div t:classappend="fields.hasErrors('email') ? 'has-error'">
    <label for="email">Email</label>
    <input type="text" t:field="*{email}" />
    <span class="help" t:errors="*{email}">Invalid email</span>
  </div>
  <select t:field="*{plan}">
    <option t:each="p : plans" t:value="p" t:text="p">BASIC</option>
  </select>
  <input type="checkbox" t:field="*{newsletter}" />
  <textarea t:field="*{address.street}"></textarea>
  <ul t:if="fields.hasErrors('*')">
    <li t:each="e : fields.errors('*')" t:text="e">error</li>
  </ul>
  <button type="submit">Sign up</button>
</form>
```

<!-- compile: java -->
```java
package com.example.guide;

public record Signup(String email, Plan plan, boolean newsletter, Address address) {}
```

<!-- compile: java -->
```java
package com.example.guide;

public enum Plan { BASIC, PRO }
```

<!-- compile: java -->
```java
package com.example.guide;

public record Address(String street) {}
```

- `t:field` on `<input>`, `<select>` and `<textarea>` writes Spring's `id`, `name` and display
  value (formatters and `@DateTimeFormat` apply; a failed bind echoes the rejected value), plus
  `checked`/`selected` and the `_name` markers of checkboxes and multi-selects, as Thymeleaf's
  `th:field` does. Checkboxes and radios get numbered ids (`roles1`, `roles2`); point labels at
  them with `ids.prev('roles')`/`ids.next('roles')`. A `t:selected` on an option replaces the
  bound-value match for that option.
- `t:errors` writes the field's messages joined by `<br />`, or removes the element if there are
  none; `*` is all errors and `global` the form object's own. For other markup, loop over
  `fields.errors('email')`.
- `fields.hasErrors(path)`, `fields.errors(path)` and `fields.value(path)` (the display value,
  JSP `spring:bind`'s `status.value`, Thymeleaf's `*{{x}}`) take a literal path, a `Field`
  parameter, or `*{...}` for an indexed path (`fields.hasErrors(*{items[itemStat.index].name})`).
- Paths index lists and maps: `*{items[itemStat.index].name}`, `*{prices[e.key]}`. This replaces
  `__${...}__` preprocessing.
- A field whose type isn't view-safe (a `MultipartFile`) can be bound where its value is never
  read: a file or password input, `t:errors`, `fields.hasErrors`. It can't be passed to a `Field`
  fragment parameter, because a `Field` carries the field's value into the fragment, whatever the
  fragment does with it. Write the file input for such a field in the page itself.

**CSRF.** A `<form method="post">` gets Spring Security's token input automatically, but only
where the compiler can see the form posts to the page's own origin. So in a POST form the `action`
is absent (it posts to the page's own URL, query string included), a static same-origin URL, or a
link with a literal path starting with `/` (or a choice of such links); `method` is static; every
`formaction`, `formmethod` and `<base href>` on the page is held to the same rule; and forms don't
nest. Any other form's computed `action` is held to the link rule too, since client code may submit
it and swap in the response. A static action to another origin is allowed and gets no token. The
compiler's error names the fix in each case. A form that writes its own `_csrf` hidden input as a
direct, unconditional child keeps it and gets no second one.

For htmx or `fetch`, write `<t:csrf-meta/>` in the layout's `<head>`. It writes Spring Security's
`<meta name="_csrf" content="...">` and `<meta name="_csrf_header" content="...">` with the
request's token and header name (`X-CSRF-TOKEN` by default, `X-XSRF-TOKEN` with the cookie
repository), or nothing when the request has no token (no Spring Security, or CSRF off). It takes
no attributes and no content; to write it on some pages only, put it in a `<t:block t:if="...">`.
The script reads both tags, `document.querySelector('meta[name="_csrf"]').content` and the same for
`_csrf_header`, and sends the token in that header (htmx: set `event.detail.headers[header] = token`
in an `htmx:configRequest` listener). A computed `content` on a `<meta name="_csrf">` is a build error. Use
htmx 2 or set `htmx.config.selfRequestsOnly = true`. A `method="post"` form that only htmx submits
gets the token input too, and htmx 2 puts it in the URL of an `hx-delete`: add
`hx-params="not _csrf"` there.

- The tags put the token on every page whose layout has them, not only on pages with a POST form,
  and load a deferred token there (with the default repository, creating a session). Spring
  Security sends `Cache-Control: no-store` unless the application sets its own: don't let a
  shared cache keep such a page (`Cache-Control: public`, a CDN rule), or it serves one user's token
  to another.
- Before Spring Security 6 (Boot 1.5 to 2.7) the token is the same in every response of a session.
  With HTTP compression on (`server.compression.enabled`, off by default) and a page that echoes
  request input, an attacker who can watch response sizes may recover it (BREACH). Use Spring
  Security's `XorCsrfTokenRequestAttributeHandler` (5.8 and later), which masks the token in each
  response as 6 and later do by default, or don't compress HTML.
- htmx's `hx-boost` swaps the `<body>` but not the `<head>`, so after a boosted login, which
  changes the token, the tags keep the old one and the next htmx request gets a 403. Don't boost
  the login form, or reload the page after it.

## Fragments and layouts

A fragment is an element with `t:fragment`. Parameters are typed, and a fragment sees only its
parameters. A fragment name may join its parts with `-` (`period-selector`), as in Thymeleaf.

<!-- compile: template guide/fragments/cards.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Customer">
  <section t:fragment="card(String title, Fragment content)" class="card">
    <h3 t:text="title">Title</h3>
    <div t:replace="content">content</div>
  </section>
  <span t:fragment="badge(Customer customer)" t:text="customer.name">Ann</span>
  <footer t:fragment="copyright">© Example</footer>
</div>
```

`t:insert` keeps the host element and makes the fragment its body; `t:replace` replaces the host:

<!-- compile: template guide/cards-page.html -->
```html
<main xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Customer" t:params="Customer customer">
  <div t:replace="~{guide/fragments/cards :: card('Profile', ~{::dl})}">
    <dl><dt>Name</dt><dd t:text="customer.name">Ann</dd></dl>
  </div>
  <p t:insert="~{guide/fragments/cards :: badge(customer=customer)}"></p>
  <div t:replace="guide/fragments/cards :: copyright"></div>
</main>
```

- References: `~{path :: name(args)}`, `path :: name(args)`, `~{::name}` (same file), or a
  `Fragment` value. Paths are relative to the templates root, without `.html`. Arguments are
  positional or all named, and type-checked; every call passes every argument (no defaults).
  Where a Thymeleaf call omitted an optional parameter, pass `null` and test for it in the
  fragment (`t:if="glyph != null"`). If most calls pass `null`, split the fragment in two: a
  shorter one with only the common parameters, and a longer one that takes the extra parameters,
  calls the shorter one and adds its extra markup around that call.
- `~{::name}` is a `Fragment` over the same-file fragment `name`, or else the first element with
  tag `name` (`~{::dl}`, `~{::my-card}`, `~{::t:block}`), rendered with the call site's variables.
  Put it inside the host so it doesn't also render in place. Only the first match; no `#id` or
  `.class` selectors.
- A `null` `Fragment` renders nothing, so a `Fragment` parameter can be optional.
- A fragment with parameters renders nothing where it is declared; Thymeleaf rendered it there too,
  reading its parameters from the variables around it (usually unset, so `null`). In one fragment
  inside another, `thwart-migrate` adds that call; pass any value those variables had.
- The compiler checks each fragment where it is inserted, so a `~{::tr}` goes into a `<tbody>`
  but not a `<div>`. Nothing goes inside `<title>`, `<textarea>` or `<noscript>`: use `t:text`.
- Every `t:if`, `t:unless`, `t:case` and `t:each` body, and every fragment, must close what it
  opens and leave open what it finds open, so what follows parses the same however often it is
  written (`[verifier:UNBALANCED]`). Two shapes need a change when porting:
  - Rows directly in a `<table>`: a browser opens a `<tbody>` before the first row only. Write the
    `<tbody>` (`<table><tbody><tr t:each="...">`), or put the `t:each` on the `<tbody>`. A browser
    adds no `<tbody>` to an empty table, so if there can be no rows, put the `t:if` or `t:unless`
    on the written `<tbody>`.
  - A fragment library wrapped in `<html>`, with a fragment call or `t:if` before its `<body>`:
    that content makes a browser open the `<body>` (or `<head>`). Make the library's root a
    `<t:block>` instead of `<html>`, or move the fragments into the `<body>`.
  - `<head t:replace="~{fragments/head :: head('Home')}">` or `<body t:replace="...">`: the
    fragment brings its own `<head>` or `<body>` where a browser has already opened one. Keep
    the page's `<head>` and replace a `<t:block>` inside it with a fragment of the head's content
    (below), or put a layout on `<html>` (Layouts).

  A layout call on the page's `<html>` (below) is fine: it ends the document.

<!-- compile: template guide/fragments/head.html -->
```html
<t:block xmlns:t="https://thwart.chainguard.dev">
  <t:block t:fragment="common(String title)">
    <title t:text="|${title} - Example|">Example</title>
    <link rel="stylesheet" t:href="@{/css/app.css}" />
  </t:block>
</t:block>
```

<!-- compile: template guide/head-page.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev">
<head>
  <t:block t:replace="~{guide/fragments/head :: common('Home')}"></t:block>
</head>
<body><h1>Home</h1></body>
</html>
```

**Layouts.** A layout is a fragment with `Fragment` parameters. Each page replaces itself with the
layout and passes its `<body>`, marked `t:remove="tag"` so that only the body's children are
inserted:

<!-- compile: template guide/fragments/layout.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev" t:fragment="layout(Fragment content, String title, Fragment head)">
<head>
  <title t:text="|${title} - Example|">Example</title>
  <link rel="stylesheet" t:href="@{/css/app.css}" />
  <t:block t:replace="head"></t:block>
</head>
<body>
  <nav><a t:href="@{/}">Home</a></nav>
  <div class="container" t:insert="content">content</div>
</body>
</html>
```

<!-- compile: template guide/home.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Customer" t:params="Customer customer"
      t:replace="~{guide/fragments/layout :: layout(~{::body}, 'Home', null)}">
<body t:remove="tag">
  <h1 t:text="|Welcome, ${customer.name}|">Welcome</h1>
</body>
</html>
```

This replaces decorator layouts (`layout:decorate`, SiteMesh, Tiles, Mustache parents). What a
decorator merged, the layout takes as parameters: the title, page-specific `<head>` content as a
`Fragment` (or `null`), the active menu entry. A page has one `<html>`, `<head>` and `<body>`: a
second one, often from passing `~{::body}` without `t:remove="tag"`, is a build error.
`t:remove="tag"` drops the element's own attributes, so move a `<body class>` to a layout
parameter. Nothing may come before the layout call. A layout that extends another layout replaces
itself with the outer one and passes its own markup:

<!-- compile: template guide/fragments/toolbar-layout.html -->
```html
<html xmlns:t="https://thwart.chainguard.dev" t:fragment="toolbarLayout(String title, Fragment tools, Fragment content)"
      t:replace="~{guide/fragments/layout :: layout(~{::t:block}, title, null)}">
<t:block>
  <div class="toolbar" t:insert="tools">tools</div>
  <div t:replace="content">content</div>
</t:block>
</html>
```

<!-- compile: template guide/report.html -->
```html
<!DOCTYPE html>
<html xmlns:t="https://thwart.chainguard.dev" t:params="String name"
      t:replace="~{guide/fragments/toolbar-layout :: toolbarLayout('Report', ~{::page-tools}, ~{::body})}">
<page-tools t:remove="tag"><a t:href="@{/report.csv}">CSV</a></page-tools>
<body t:remove="tag">
  <h1 t:text="name">Report</h1>
</body>
</html>
```

**Reusable form fields.** A `Field` parameter takes a field path that the compiler checks against
the caller's `t:object`. `Field<T>` also binds the type's properties (`t:field="c.answer"`), which
replaces JSP's `path="${field}.answer"` and Thymeleaf's `*{__${field}__.answer}`:

<!-- compile: template guide/fragments/fields.html -->
```html
<div xmlns:t="https://thwart.chainguard.dev">
  <div t:fragment="input(String label, Field name, String type)" class="form-group"
       t:classappend="fields.hasErrors(name) ? 'has-error'">
    <label t:for="name" t:text="label">Label</label>
    <t:block t:switch="type">
      <input t:case="'email'" type="email" t:field="name" />
      <input t:case="*" type="text" t:field="name" />
    </t:block>
    <span t:errors="name">Error</span>
  </div>
</div>
```

<!-- compile: template guide/profile.html -->
```html
<form xmlns:t="https://thwart.chainguard.dev" t:import="com.example.guide.Signup" t:params="Signup signup"
      t:object="signup" method="post">
  <div t:replace="~{guide/fragments/fields :: input('Email', 'email', 'text')}"></div>
  <div t:replace="~{guide/fragments/fields :: input('Street', 'address.street', 'text')}"></div>
</form>
```

Printing a `Field` writes the field's id, so `<label t:for="name">` points at the input. A caller
can't pass a `Field` indexed by its own loop variable: keep those controls at the call site and pass
them in as a `Fragment`, or pass the list as a `Field<List<T>>` and index it inside.

## Rendering

With Spring, `return "owners/list";` renders `templates/owners/list.html` from the model (one
leading `/` is dropped; other names fall through to other resolvers). Path variables fill
parameters of the same name. A model value of the wrong type fails with `TemplateModelException`.
`return "owners/list :: rows";` renders only the fragment `rows`, its parameters filled from the
model, for an htmx or Turbo partial response; arguments and selectors in the view name aren't
supported. A partial of `<option>`s is declared outside the `<select>`, in its own template
(`<t:block t:fragment="options(List<Team> teams)"><option t:each="...">`); the page's `<select>`
holds `<t:block t:replace="~{teams/options :: options(teams)}">`; the htmx handler returns
`"teams/options :: options"`. A `<select t:field>` can't take inserted options (none would be
selected): the partial is the whole select, in a `t:fragment` that also holds the `t:object`.
Don't return a `Template` from a controller: return the view name.

Outside a request (a mail job, a command-line tool), call
`XTemplate.of(...).renderToString(ctx)` with a `RenderContext` (`RenderContext.defaults()`,
`ResourceBundleMessages`, `DefaultLinks(contextPath)`), or in a controller with
`SpringRenderContexts.create(request, response, model)`. `Fragment` exists only between
templates: Java can't build one, and a page (`t:params`) or a function can't take one. A page that
took one becomes a fragment its callers pass `~{...}`.

**Content Security Policy.** With the Spring starter every `<script>` and `<style>` the template
writes gets the request's nonce, and by default the page is sent with `Content-Security-Policy:
script-src 'nonce-N' 'strict-dynamic'; object-src 'none'; base-uri 'none'`. A policy the
application sets wins (Spring Security's `contentSecurityPolicy(...)`, or your filter's header with
`CspNonce.get(request)`): `dev.chainguard.thwart.spring.view.CspFilter` adds the default only when the
response commits without one. Boot and servlet containers register it; elsewhere (your own embedded
server, a `metadata-complete` `web.xml`, Boot importing `ThwartConfiguration`) register it first,
for REQUEST, ASYNC and ERROR, or the starter logs `CspFilter is not in the filter chain`. The one
off switch is `thwart.csp.enabled=false`. Delete `th:nonce`.

**A third-party script that needs `eval`.** The default policy has no `'unsafe-eval'`, so a library
that calls `eval` or `new Function` (an old `owl.carousel.min.js`, `jquery-rotate.js`) throws an
`EvalError`, and whatever it set up is missing (`owl.owlCarousel is not a function`). Neither the
build, the tests nor `compare` see it; the browser console does. Prefer replacing or upgrading the
library. Otherwise allow `eval` in a policy of your own, a visible choice that the starter's
default then stands aside for (above). It gives up part of the CSP backstop:
any script on those pages, not just that library, may then run a string as code, including DOM
text or attributes it reads, so limit it to the pages that need it (for example with a
`FilterRegistrationBean` and URL patterns). It must keep the nonce
(`dev.chainguard.thwart.spring.CspNonce`), or no script runs:

```java
@Bean
OncePerRequestFilter cspAllowingEval() { // for jquery-rotate.js; delete with it
  return new OncePerRequestFilter() {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
      response.setHeader("Content-Security-Policy", "script-src 'nonce-" + CspNonce.get(request)
          + "' 'strict-dynamic' 'unsafe-eval'; object-src 'none'; base-uri 'none'");
      chain.doFilter(request, response);
    }
  };
}
```
