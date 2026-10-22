package dev.tessera.spring.context;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Test class loader that simulates an application without Spring Security: it defines the
 * starter's own {@code dev.tessera.spring.*} classes itself and refuses
 * {@code org.springframework.security.*}.
 */
public final class NoSecurityClassLoader extends URLClassLoader {
  public NoSecurityClassLoader() {
    super(
        new URL[] {SpringRenderContexts.class.getProtectionDomain().getCodeSource().getLocation()},
        SpringRenderContexts.class.getClassLoader());
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      if (name.startsWith("org.springframework.security.")) {
        throw new ClassNotFoundException(name);
      }
      if (name.startsWith("dev.tessera.spring.")) {
        Class<?> c = findLoadedClass(name);
        return c != null ? c : findClass(name);
      }
      return super.loadClass(name, resolve);
    }
  }
}
