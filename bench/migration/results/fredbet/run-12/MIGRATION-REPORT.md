# Migration report: Thymeleaf → Tessera

## Summary

All server-rendered pages of FredBet are now Tessera templates, compiled into Java classes during
`javac` (`de.fred4jupiter.fredbet.templates.*`). Thymeleaf, the Thymeleaf Layout Dialect and
`thymeleaf-extras-springsecurity6` are removed from the build, as is the Thymeleaf configuration
(`spring.thymeleaf.cache` in `application-dev.yml`).

## What changed

### Build (`pom.xml`)
- `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6`, `thymeleaf-layout-dialect`
  removed; `dev.tessera:tessera-spring-boot-starter` added.
- `maven-compiler-plugin`: `annotationProcessorPaths` (the Spring Boot configuration processor that
  discovery found before, then `tessera-compiler`), `-Atessera.templates`, `-Atessera.package`,
  `-Atessera.messages` (the German bundles, the default language of the app: every literal `#{key}`
  is checked at build time).
- `tessera-maven-plugin:check` so template-only/message-only edits recompile.

### Shared layout
- `_layout.html` is a fragment `layout(title, head, content, scripts, layout, globalMessage)`;
  every page replaces its `<html>` with it (this replaces `layout:decorate` / `layout:fragment` /
  `layout:title-pattern`).
- `_navigation.html`, `fragments/*` are typed fragments (`menu(LayoutView)`,
  `language_selector(Map)`, `alert_headline(WebMessage)`, `delete_confirm_modal(String, String)`,
  `profile_image_small_with_popup(String)`).
- `web/view/LayoutModelAdvice` (`@ControllerAdvice`) builds a `LayoutView` record for every page:
  everything the navigation used to get from `@webSecurityUtil`, `@runtimeSettingsUtil`,
  `@groupAvailabilityUtil`, `@languageUtil` and `@languageIconUtil` bean calls.
- `sec:authorize` → `auth.hasAuthority(...)` / `auth.hasAnyAuthority(...)`.
- The Bootswatch theme stylesheet (a computed URL, which Tessera does not allow in `<link href>`) is
  one static `<link>` per `Theme` constant under `t:if`; the output is identical.
- `web/view/ViewFunctions` (`teams.css(Country)`, the former `@teamUtil.cssClassFor`; the logic in
  `TeamUtil` became static so both share it) and `web/view/MessageFunctions` (`msgs.get(key)`, for
  message keys computed from data such as `Group.titleMsgKey`, which Tessera cannot express as `#{...}`).
- Error page: `ApplicationErrorViewResolver` adds `timestampText` (the `Date.toString()` Thymeleaf
  printed) since `java.util.Date` is not view-safe.
- `LoginController` puts `selfRegistrationEnabled` in the model.

PAGES_SECTION

## Verification

VERIFY_SECTION

## Could not convert / intended differences

DIFF_SECTION

## Where Tessera or its documentation fell short

SHORT_SECTION
