package com.example;

import com.example.templates.HelloTemplate;
import dev.tessera.runtime.DefaultLinks;
import dev.tessera.runtime.Forms;
import dev.tessera.runtime.RenderContext;
import dev.tessera.runtime.ResourceBundleMessages;
import java.util.Locale;

/** Renders templates/hello.html without a web framework. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    RenderContext ctx =
        new RenderContext(
            Locale.ROOT, new ResourceBundleMessages("messages", Locale.ROOT), new DefaultLinks(""), Forms.NONE);
    System.out.println(HelloTemplate.of(new Greeting("Ann <b>")).renderToString(ctx));
  }
}
