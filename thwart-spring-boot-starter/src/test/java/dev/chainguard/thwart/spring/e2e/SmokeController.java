package dev.chainguard.thwart.spring.e2e;

import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
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

  /** A page with a style and two scripts, which get the request's CSP nonce (#335). */
  @GetMapping("/csp")
  public String csp() {
    return "smoke/csp";
  }

  /** POST forms whose action comes from the request (#285). */
  @GetMapping("/action")
  public String action(@RequestParam("target") String target, Model model) {
    model.addAttribute("target", target);
    return "smoke/action";
  }

  /**
   * {@code smoke/owner} declares {@code Long ownerId, String name}; the path variables fill them
   * (#124). {@code petId} is not declared, so the template never sees it.
   */
  @GetMapping("/owners/{ownerId}/pets/{petId}")
  public String owner(
      @PathVariable("ownerId") Long ownerId, @PathVariable("petId") String petId) {
    return "smoke/owner";
  }

  /** A model attribute wins over the path variable of the same name. */
  @GetMapping("/owners/{ownerId}/as/{name}")
  public String ownerAs(
      @PathVariable("ownerId") Long ownerId, @PathVariable("name") String name, Model model) {
    model.addAttribute("name", "model-" + name);
    return "smoke/owner";
  }

  /** A path variable of the wrong type fails the render, like a model attribute. */
  @GetMapping("/owners/named/{ownerId}")
  public String ownerNamed(@PathVariable("ownerId") String ownerId) {
    return "smoke/owner";
  }

  /**
   * An htmx partial response (#131): the fragment {@code row} of {@code smoke/rows}, filled from
   * the path variable and the model by its own declared params. {@code secret} is not one of them.
   */
  @GetMapping("/owners/{ownerId}/row")
  public String ownerRow(@PathVariable("ownerId") Long ownerId, Model model) {
    model.addAttribute("name", "Ann");
    model.addAttribute("secret", "s3cret");
    return "smoke/rows :: row";
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
