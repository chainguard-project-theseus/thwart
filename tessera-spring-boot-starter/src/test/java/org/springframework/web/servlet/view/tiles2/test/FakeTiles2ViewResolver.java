package org.springframework.web.servlet.view.tiles2.test;

import java.util.Locale;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Stands in for Spring 4.3's Tiles 2 view resolver ({@code
 * org.springframework.web.servlet.view.tiles2.TilesViewResolver}) without Tiles on the classpath:
 * the startup warning matches the {@code org.springframework.web.servlet.view.tiles2.} package by
 * name. Resolves nothing.
 */
public class FakeTiles2ViewResolver implements ViewResolver {

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    return null;
  }
}
