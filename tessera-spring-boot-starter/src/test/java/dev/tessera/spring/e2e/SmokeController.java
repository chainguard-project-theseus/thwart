package dev.tessera.spring.e2e;

import dev.tessera.spring.e2e.templates.smoke.PageTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/** Controllers over the compiled {@code smoke/page} template, shared by the end-to-end tests. */
@Controller
public class SmokeController {

  @GetMapping("/page")
  public String page(Model model) {
    model.addAttribute("signup", new Signup("Ada"));
    return "smoke/page";
  }

  @GetMapping("/typed")
  public PageTemplate typed() {
    return PageTemplate.of(new Signup("Grace"));
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
