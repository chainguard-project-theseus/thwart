package dev.tessera.spring.view;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

/**
 * Stands in for "the next resolver" after Tessera's: records every name it is asked for and
 * renders it as {@code fallback:<name>} plain text. {@code redirect:} names are left to Spring.
 */
public final class FallbackResolver implements ViewResolver, Ordered {

  public final List<String> names = new CopyOnWriteArrayList<>();

  @Override
  public View resolveViewName(String viewName, Locale locale) {
    names.add(viewName);
    if (viewName.startsWith("redirect:")) {
      return null;
    }
    return new View() {
      @Override
      public String getContentType() {
        return "text/plain;charset=UTF-8";
      }

      @Override
      public void render(
          Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
          throws Exception {
        response.setContentType(getContentType());
        response.getWriter().write("fallback:" + viewName);
      }
    };
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 1;
  }
}
