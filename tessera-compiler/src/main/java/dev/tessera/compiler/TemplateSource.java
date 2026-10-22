package dev.tessera.compiler;

/**
 * One template file.
 *
 * @param viewName path relative to the templates root without {@code .html}, using {@code /}
 *     (e.g. {@code owners/ownerDetails}); this is the name controllers return
 * @param path path relative to the templates root, used in diagnostics
 * @param content the file contents (UTF-8 decoded)
 */
public record TemplateSource(String viewName, String path, String content) {}
