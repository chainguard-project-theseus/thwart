package dev.tessera.benchmarks;

import dev.tessera.benchmarks.templates.HomeTemplate;
import dev.tessera.benchmarks.templates.OwnersTemplate;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.StringOutput;
import java.util.List;
import java.util.Map;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Renders the benchmark pages with each engine. Used by the benchmarks and the equivalence test. */
public final class Engines {
  private Engines() {}

  /** A Thymeleaf engine over {@code src/main/resources/thymeleaf}, caching parsed templates. */
  public static TemplateEngine thymeleaf() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver(Engines.class.getClassLoader());
    resolver.setPrefix("thymeleaf/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    resolver.setCacheable(true);
    TemplateEngine engine = new TemplateEngine();
    engine.setTemplateResolver(resolver);
    engine.setLinkBuilder(new RootContextLinkBuilder());
    return engine;
  }

  public static String thymeleafOwners(TemplateEngine engine, List<Owner> owners, int page) {
    return engine.process("owners", new Context(null, Map.of("owners", owners, "page", page)));
  }

  public static String thymeleafHome(TemplateEngine engine, List<Owner> owners) {
    return engine.process("home", new Context(null, Map.of("owners", owners)));
  }

  public static String tesseraOwners(RenderContext ctx, List<Owner> owners, int page) {
    StringOutput out = new StringOutput(32 * 1024);
    OwnersTemplate.render(owners, page, ctx, out);
    return out.toString();
  }

  public static String tesseraHome(RenderContext ctx, List<Owner> owners) {
    StringOutput out = new StringOutput(8 * 1024);
    HomeTemplate.render(owners, ctx, out);
    return out.toString();
  }

  /**
   * Outside a web request Thymeleaf refuses context-relative links ({@code @{/x}}); give it the
   * same empty context path that {@link RenderContext#defaults()} gives Tessera.
   */
  private static final class RootContextLinkBuilder extends StandardLinkBuilder {
    @Override
    protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
      return "";
    }
  }
}
