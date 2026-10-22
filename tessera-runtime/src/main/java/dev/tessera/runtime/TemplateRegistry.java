package dev.tessera.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Build-time registry of compiled templates. The annotation processor generates one
 * implementation per compilation unit (module) and registers it in
 * {@code META-INF/services/dev.tessera.runtime.TemplateRegistry}. View names are looked up
 * here; an unknown name is simply absent, never parsed.
 */
public interface TemplateRegistry {

  Optional<TemplateEntry> find(String viewName);

  Collection<TemplateEntry> all();

  /** Merges every registry visible to {@code loader}. Duplicate view names are an error. */
  static TemplateRegistry load(ClassLoader loader) {
    Map<String, TemplateEntry> entries = new LinkedHashMap<>();
    for (TemplateRegistry r : ServiceLoader.load(TemplateRegistry.class, loader)) {
      for (TemplateEntry e : r.all()) {
        if (entries.putIfAbsent(e.name(), e) != null) {
          throw new IllegalStateException("Duplicate Tessera template name: " + e.name());
        }
      }
    }
    return of(List.copyOf(entries.values()));
  }

  /** A registry over a fixed list of entries. */
  static TemplateRegistry of(List<TemplateEntry> list) {
    Map<String, TemplateEntry> byName = new LinkedHashMap<>();
    for (TemplateEntry e : list) {
      byName.put(e.name(), e);
    }
    return new TemplateRegistry() {
      @Override
      public Optional<TemplateEntry> find(String viewName) {
        return Optional.ofNullable(byName.get(viewName));
      }

      @Override
      public Collection<TemplateEntry> all() {
        return List.copyOf(byName.values());
      }
    };
  }
}
