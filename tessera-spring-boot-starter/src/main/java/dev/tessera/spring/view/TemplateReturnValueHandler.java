package dev.tessera.spring.view;

import dev.tessera.runtime.Template;
import java.util.Objects;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers return a typed template, e.g. {@code return OwnerDetailsTemplate.of(owner);}.
 * The bound template is rendered as a {@link TesseraView}; no view name is involved.
 */
public final class TemplateReturnValueHandler implements HandlerMethodReturnValueHandler {

  private final RenderContextFactory contexts;

  public TemplateReturnValueHandler(RenderContextFactory contexts) {
    this.contexts = Objects.requireNonNull(contexts, "contexts");
  }

  @Override
  public boolean supportsReturnType(MethodParameter returnType) {
    return Template.class.isAssignableFrom(returnType.getParameterType());
  }

  @Override
  public void handleReturnValue(
      Object returnValue,
      MethodParameter returnType,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest) {
    if (!(returnValue instanceof Template template)) {
      throw new IllegalStateException(
          "Controller method " + returnType.getMethod() + " returned a null Template");
    }
    mavContainer.setView(TesseraView.of(template, contexts));
  }
}
