package dev.tessera.spring;

import dev.tessera.spring.view.RenderContextFactory;
import dev.tessera.spring.view.TemplateReturnValueHandler;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Appends a {@link TemplateReturnValueHandler} to every {@link RequestMappingHandlerAdapter}'s
 * custom return-value handlers before it initializes. Unlike a {@code WebMvcConfigurer}, this
 * works the same with Boot (any version) and with plain {@code @EnableWebMvc} or XML MVC config.
 */
final class ReturnValueHandlerRegistrar implements BeanPostProcessor {

  private final ObjectProvider<RenderContextFactory> contexts;

  ReturnValueHandlerRegistrar(ObjectProvider<RenderContextFactory> contexts) {
    this.contexts = contexts;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof RequestMappingHandlerAdapter) {
      RequestMappingHandlerAdapter adapter = (RequestMappingHandlerAdapter) bean;
      List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
      if (adapter.getCustomReturnValueHandlers() != null) {
        handlers.addAll(adapter.getCustomReturnValueHandlers());
      }
      handlers.add(new TemplateReturnValueHandler(contexts.getObject()));
      adapter.setCustomReturnValueHandlers(handlers);
    }
    return bean;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    return bean;
  }
}
