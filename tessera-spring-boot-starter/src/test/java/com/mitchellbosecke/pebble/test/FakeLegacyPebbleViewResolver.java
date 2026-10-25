package com.mitchellbosecke.pebble.test;

import java.util.Locale;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Stands in for Pebble's view resolver up to 3.1 ({@code
 * com.mitchellbosecke.pebble.spring.PebbleViewResolver}, {@code ...spring4.} and {@code
 * ...spring.servlet.}) without Pebble on the classpath: the startup warning matches the {@code
 * com.mitchellbosecke.pebble.} package by name. Resolves nothing.
 */
public class FakeLegacyPebbleViewResolver implements ViewResolver {

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    return null;
  }
}
