package dev.chainguard.thwart.spring.e2e;

import dev.chainguard.thwart.runtime.TemplateView;

/** Form bean for {@code templates/smoke/page.html} (Java 8: a getter class, not a record). */
@TemplateView
public class Signup {
  private String name;

  public Signup() {}

  public Signup(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
