package io.pebbletemplates.test;

import java.util.Locale;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Stands in for Pebble 3.2+'s view resolver ({@code
 * io.pebbletemplates.spring.servlet.PebbleViewResolver}) without Pebble on the classpath: the
 * startup warning matches the {@code io.pebbletemplates.} package by name. Resolves nothing.
 */
public class FakePebbleViewResolver implements ViewResolver {

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    return null;
  }
}
