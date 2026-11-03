package com.example;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Renders templates/hello.html through the starter's view resolver. */
@Controller
class HelloController {
  @GetMapping("/")
  String hello(@RequestParam(defaultValue = "world") String name, Model model) {
    model.addAttribute("greeting", new Greeting(name));
    return "hello";
  }
}
