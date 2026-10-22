package dev.tessera.spring.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tessera.spring.context.NoSecurityClassLoader;
import org.junit.jupiter.api.Test;

/** Loads {@link Auth} in a class loader that hides Spring Security, as in an app without it. */
class AuthWithoutSecurityTest {

  @Test
  void everyFunctionIsSafeWithoutSpringSecurity() throws Exception {
    try (var loader = new NoSecurityClassLoader()) {
      assertThrows(
          ClassNotFoundException.class,
          () -> loader.loadClass("org.springframework.security.core.context.SecurityContextHolder"));
      Class<?> auth = loader.loadClass(Auth.class.getName());
      assertEquals(loader, auth.getClassLoader());
      assertEquals(false, auth.getMethod("isAuthenticated").invoke(null));
      assertEquals(false, auth.getMethod("hasRole", String.class).invoke(null, "USER"));
      assertEquals(
          false,
          auth.getMethod("hasAnyRole", String[].class).invoke(null, (Object) new String[] {"USER"}));
      assertNull(auth.getMethod("name").invoke(null));
    }
  }
}
