package dev.tessera.spring.e2e;

import dev.tessera.spring.e2e.templates.smoke.PageTemplate;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Controllers over the compiled {@code smoke/page} template, shared by the end-to-end tests. */
@Controller
public class SmokeController {

  @GetMapping("/page")
  public String page(Model model) {
    model.addAttribute("signup", new Signup("Ada"));
    return "smoke/page";
  }

  /** POST forms whose action comes from the request (#285). */
  @GetMapping("/action")
  public String action(@RequestParam("target") String target, Model model) {
    model.addAttribute("target", target);
    return "smoke/action";
  }

  @GetMapping("/typed")
  public PageTemplate typed() {
    return PageTemplate.of(new Signup("Grace"));
  }

  /** A bound cart (so the model has a BindingResult, as for any {@code @ModelAttribute}). */
  @GetMapping("/cart")
  public String cart(@ModelAttribute("cart") Cart cart) {
    cart.getLines().add(new Line("Tea", LocalDate.of(2024, 3, 5)));
    cart.getLines().add(new Line("Cake", null));
    return "smoke/cart";
  }

  @PostMapping("/cart")
  public String saveCart(@ModelAttribute("cart") Cart cart, BindingResult result) {
    for (int i = 0; i < cart.getLines().size(); i++) {
      String name = cart.getLines().get(i).getName();
      if (name == null || name.isEmpty()) {
        result.rejectValue("lines[" + i + "].name", "required", "Name is required");
      }
    }
    return result.hasErrors() ? "smoke/cart" : "redirect:/cart";
  }

  @PostMapping("/signup")
  public String signup(@ModelAttribute("signup") Signup signup, BindingResult result) {
    if (signup.getName() == null || signup.getName().isEmpty()) {
      result.rejectValue("name", "required", "Name is required");
      return "smoke/page";
    }
    return "redirect:/page";
  }
}
