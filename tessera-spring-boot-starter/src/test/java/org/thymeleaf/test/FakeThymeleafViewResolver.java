package org.thymeleaf.test;

import java.util.Locale;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Stands in for Thymeleaf's view resolver ({@code org.thymeleaf.spring6.view.ThymeleafViewResolver}
 * and its spring4/spring5 twins) without a Thymeleaf dependency: the startup warning matches the
 * {@code org.thymeleaf.} package by name. Resolves nothing.
 */
public class FakeThymeleafViewResolver implements ViewResolver {

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    return null;
  }
}
