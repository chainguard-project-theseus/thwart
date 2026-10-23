package dev.tessera.spring.e2e;

import dev.tessera.runtime.TemplateView;
import java.util.ArrayList;
import java.util.List;

/** Form bean with a list of lines for {@code templates/smoke/cart.html} (indexed paths, #123). */
@TemplateView
public class Cart {
  private List<Line> lines = new ArrayList<Line>();

  public List<Line> getLines() {
    return lines;
  }

  public void setLines(List<Line> lines) {
    this.lines = lines;
  }
}
