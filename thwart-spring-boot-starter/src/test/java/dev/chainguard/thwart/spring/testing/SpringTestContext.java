package dev.chainguard.thwart.spring.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.springframework.test.context.TestContextManager;

/**
 * Runs the Spring TestContext framework ({@code @SpringBootTest}, {@code @WebMvcTest},
 * {@code @Autowired} fields) on the JUnit Platform for every Spring generation the shared tests
 * cover. Spring's own {@code SpringExtension} needs Spring 5.0; this drives the same {@link
 * TestContextManager} lifecycle, which Spring 4.3 already has.
 */
public final class SpringTestContext
    implements BeforeAllCallback,
        TestInstancePostProcessor,
        BeforeEachCallback,
        AfterEachCallback,
        AfterAllCallback {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(SpringTestContext.class);

  // computeIfAbsent, the replacement, is missing from the older JUnit 5 releases this runs on.
  @SuppressWarnings("deprecation")
  private static TestContextManager manager(ExtensionContext context) {
    Class<?> testClass = context.getRequiredTestClass();
    return context
        .getRoot()
        .getStore(NAMESPACE)
        .getOrComputeIfAbsent(testClass, TestContextManager::new, TestContextManager.class);
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    manager(context).beforeTestClass();
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context)
      throws Exception {
    manager(context).prepareTestInstance(testInstance);
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    manager(context)
        .beforeTestMethod(context.getRequiredTestInstance(), context.getRequiredTestMethod());
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    manager(context)
        .afterTestMethod(
            context.getRequiredTestInstance(),
            context.getRequiredTestMethod(),
            context.getExecutionException().orElse(null));
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    manager(context).afterTestClass();
  }
}
