package dev.tessera.compiler.types;

import javax.lang.model.type.TypeMirror;

/**
 * A variable visible to expressions.
 *
 * @param name template-level name ({@code [A-Za-z_][A-Za-z0-9_]*}); names containing
 *     {@code $} are internal (unreachable from templates)
 * @param type static type
 * @param javaName Java local/parameter name in generated code
 */
public record Variable(String name, TypeMirror type, String javaName) {}
