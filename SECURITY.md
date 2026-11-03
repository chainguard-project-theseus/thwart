# Security policy

Tessera is a template engine for Java web applications that removes whole classes of template
vulnerability by design. The threat model, with the one trust table, is
[docs/security-model.md](docs/security-model.md); how the guarantee is built is
[docs/security-architecture.md](docs/security-architecture.md); the HTML profile the compiler
accepts, rule by rule, is [docs/context-model.md](docs/context-model.md).

## Reporting a vulnerability

Don't open a public issue. Follow the wolfi-dev
[security policy](https://github.com/wolfi-dev/.github/blob/main/SECURITY.md).

## Summary

- Templates compile to plain Java at build time. The runtime has no template parser and no
  expression evaluator, so nothing at render time turns data into template code.
- A template sees only its typed `t:params`, view-safe types and registered functions.
- Every model value is untrusted. The compiler picks each value's escaper from its context.
- Raw output takes only `SafeHtml`, `SafeInlineHtml` or a compiled `Fragment`; HTML messages are
  sanitized when rendered.
- Static markup in a template can't hold an `on*` event handler or a `javascript:` URL in an HTML
  URL attribute: both are build errors.
- The Spring starter sends a strict nonce-based Content-Security-Policy with every Tessera page
  that has none, and the compiler puts the nonce on every script and style it writes.
- Client frameworks, page JavaScript, a compromised build and unsupported browsers are out of scope.

## What counts as a vulnerability

If the compiler accepts a template and a supported browser parses its rendered output differently
from the compiler's model, that is a bug, however contrived the markup. So is any way for a model
value to reach code or to leave the context the compiler chose. A template the compiler rejects
although it would have been safe is a false positive: report it as an ordinary issue.
