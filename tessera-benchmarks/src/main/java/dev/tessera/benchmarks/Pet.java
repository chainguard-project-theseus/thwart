package dev.tessera.benchmarks;

import dev.tessera.runtime.TemplateView;

/** A pet. A JavaBean rather than a record so Thymeleaf's OGNL and Tessera read the same getters. */
@TemplateView
public final class Pet {
  private final String name;
  private final String type;

  public Pet(String name, String type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }
}
