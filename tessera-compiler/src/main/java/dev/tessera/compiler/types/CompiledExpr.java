package dev.tessera.compiler.types;

import dev.tessera.compiler.SourcePos;
import javax.lang.model.type.TypeMirror;

/**
 * A type-checked expression.
 *
 * @param code a Java expression (fully qualified names, no imports needed) that evaluates the
 *     template expression; may reference {@code ctx} (the RenderContext) and variables'
 *     {@code javaName}s
 * @param type its static type
 * @param pos template position of the source expression
 */
public record CompiledExpr(String code, TypeMirror type, SourcePos pos) {}
