package dev.tessera.spring.e2e;

import dev.tessera.runtime.TemplateView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Form bean with a list of lines for {@code templates/smoke/cart.html} (indexed paths, #123; map keys, #190). */
@TemplateView
public class Cart {
  private List<Line> lines = new ArrayList<Line>();
  private Map<String, Integer> qty = new LinkedHashMap<String, Integer>();

  public Cart() {
    qty.put("tea", 1);
  }

  public List<Line> getLines() {
    return lines;
  }

  public void setLines(List<Line> lines) {
    this.lines = lines;
  }

  public Map<String, Integer> getQty() {
    return qty;
  }

  public void setQty(Map<String, Integer> qty) {
    this.qty = qty;
  }
}
