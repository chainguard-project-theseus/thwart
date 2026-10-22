package dev.tessera.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a class into the view-safe allow-list: templates may read its public no-argument
 * {@code getX()}/{@code isX()} getters (records, enums, and the JDK value types are view-safe
 * without this). Never-view-safe types stay unreachable even if annotated.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface TemplateView {}
