# Thwart guide

How to add Thwart to a Spring application, write its templates, and move its views to Thwart from
another engine.

| File | What it covers |
|---|---|
| [setup.md](setup.md) | Build setup: picking the starter, Maven, Gradle, Spring (Boot, MVC without Boot, security, error pages) |
| [templates.md](templates.md) | The model; templates and parameters, view types, expressions and attributes, output contexts, messages and links, functions, forms, fragments and layouts, rendering and the CSP |
| [migrate.md](migrate.md) | Migrating views from Thymeleaf, JSP, FreeMarker, Velocity, Mustache, Pebble or similar: the tools, how to work, what "done" is checked against, the record/convert/compare loop, hot reload, notes for specific engines |
| [limitations.md](limitations.md) | Known limitations, and the rule table for `[verifier:RULE]` errors |

Coding agents read the same files as the references of the Claude Code skill in
[plugins/thwart](../../plugins/thwart/skills/thwart/SKILL.md). Edit them here, then copy them to
`plugins/thwart/skills/thwart/references/`: `ThwartSkillTest` fails while a copy differs.
