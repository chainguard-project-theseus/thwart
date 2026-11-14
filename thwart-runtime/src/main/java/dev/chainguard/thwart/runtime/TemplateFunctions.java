package dev.chainguard.thwart.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a function namespace. Its {@code public static} methods annotated
 * {@link TemplateFunction} are callable from templates as {@code namespace.method(args)} (or
 * {@code #namespace.method(args)} for Thymeleaf familiarity).
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface TemplateFunctions {
  /** Namespace identifier, e.g. {@code fmt}. */
  String value();
}
