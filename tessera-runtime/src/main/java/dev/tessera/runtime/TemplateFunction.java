package dev.tessera.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code public static} method of a {@link TemplateFunctions} class as callable from
 * templates. Parameters and return type must not be never-view-safe types.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface TemplateFunction {}
