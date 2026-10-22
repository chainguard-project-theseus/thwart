package dev.tessera.compiler.codegen;

import dev.tessera.compiler.CompilerEnv;
import dev.tessera.compiler.GeneratedFile;
import java.util.List;

/**
 * Generates {@code <basePackage>.TesseraRegistry implements dev.tessera.runtime.TemplateRegistry}
 * (one {@code TemplateEntry} per template whose renderer adapts a model map with
 * {@code instanceof} checks; no reflection) and the
 * {@code META-INF/services/dev.tessera.runtime.TemplateRegistry} resource naming it.
 */
public final class RegistryGenerator {
  private final CompilerEnv env;
  private final TemplateIndex index;

  public RegistryGenerator(CompilerEnv env, TemplateIndex index) {
    this.env = env;
    this.index = index;
  }

  public List<GeneratedFile> generate() {
    throw new UnsupportedOperationException("TODO(#5): RegistryGenerator.generate " + env.basePackage() + index);
  }
}
