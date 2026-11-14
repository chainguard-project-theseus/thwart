package org.springframework.web.servlet.view.tiles3.test;

import java.util.Locale;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Stands in for Spring 4.3-5.3's Tiles 3 view resolver ({@code
 * org.springframework.web.servlet.view.tiles3.TilesViewResolver}; Spring 6 dropped Tiles) without
 * Tiles on the classpath: the startup warning matches the {@code
 * org.springframework.web.servlet.view.tiles3.} package by name. Resolves nothing.
 */
public class FakeTiles3ViewResolver implements ViewResolver {

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    return null;
  }
}
