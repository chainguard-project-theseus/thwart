package dev.chainguard.thwart.compiler.codegen.attr;

import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.SourcePos;
import dev.chainguard.thwart.compiler.codegen.CodegenContext;
import dev.chainguard.thwart.compiler.codegen.ElementPlan;
import dev.chainguard.thwart.compiler.codegen.Emitted;
import dev.chainguard.thwart.compiler.html.Contexts;
import dev.chainguard.thwart.compiler.html.HtmlParser;
import dev.chainguard.thwart.compiler.model.Attribute;
import dev.chainguard.thwart.compiler.model.Element;
import dev.chainguard.thwart.compiler.model.Node;
import dev.chainguard.thwart.compiler.verify.Skeleton;
import dev.chainguard.thwart.runtime.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The CSRF token, by a closed shape: the compiler adds a hidden input
 * with {@code ctx.forms().csrfToken()} after the start tag of a {@code <form>} only when it can see,
 * at build time, that the form posts to the page's origin. Everything HTML gives a browser to send
 * that form elsewhere is held to the same rule, on every element, so no rule depends on which form
 * owns a button. What client code such as htmx does with a form ({@code hx-post}, {@code hx-get},
 * a boost) is outside the guarantee: use htmx 2 or set {@code selfRequestsOnly}.
 *
 * <ul>
 *   <li>The method is a static {@code method="post"} (ASCII case-insensitive, untrimmed, as a
 *       browser reads it). Any other static value submits as GET and gets no token. A computed
 *       method on a {@code <form>} is a build error ({@code GenericAttrProcessor}).
 *   <li>The action is absent, a static URL same-origin by shape ({@link Checks#isSameOriginUrl}),
 *       or, computed ({@code t:action}), a link with a same-origin
 *       literal path, re-checked when rendered, or a choice whose every branch is one
 *       ({@code GenericAttrProcessor}); any other computed action of a static POST form is a build
 *       error. A static action on another origin is the author's visible choice: no token.
 *   <li>Every {@code formaction} and {@code formmethod}, in any form or none, is checked as if its
 *       form got the token: {@link #checkSubmitter} and {@code GenericAttrProcessor}.
 *   <li>Every static {@code <base href>} is same-origin by shape: {@link #checkBase}; a computed
 *       one is a same-origin link already ({@code GenericAttrProcessor}).
 *   <li>A nested {@code <form>} is a verifier {@code PARSE_ERROR}, also through fragments and every
 *       {@code Fragment} value a template builds: each is checked where it lands ({@code
 *       FragmentCandidates}). Java can't build a {@code Fragment}.
 *       {@code <form/>} is an error here.
 * </ul>
 *
 * <p>A form that writes its own token input keeps it and gets no second one: a direct child
 * {@code <input type="hidden">} whose literal {@code name} equals the token's parameter name at
 * render time ({@link #ownTokenNames}).
 */
public final class CsrfInjector {
  private CsrfInjector() {}

  /** Adds the token input to a {@code <form>} in the closed shape. */
  public static void apply(CodegenContext ctx, ElementPlan plan) {
    if (plan.removed() || plan.replacement() != null || plan.tagOmitted()) {
      // Without its tag (t:remove="tag") the token input would land in an enclosing form, maybe a GET one.
      return;
    }
    if (plan.element().selfClosing() && plan.body() == null) {
      // A browser treats <form/> as an open form: the page after it is inside the form, and the start
      // tags of later forms are dropped, so their buttons and token inputs land in it.
      ctx.diagnostics()
          .error(plan.element().pos(), "<form/> is not self-closing in HTML: the browser treats everything after it"
              + " as inside the form; write <form ...></form>");
      return;
    }
    Optional<ElementPlan.Attr> method = plan.attribute("method");
    Optional<ElementPlan.Attr> action = plan.attribute("action");
    if (!isStaticPost(plan.element()) || !method.isPresent() || method.get() instanceof ElementPlan.DynamicAttr
        || (action.isPresent() && action.get() instanceof ElementPlan.StaticAttr
            && !isSameOriginStatic(((ElementPlan.StaticAttr) action.get()).source().value()))) {
      return;
    }
    // A computed action here is a same-origin link or a choice of them (GenericAttrProcessor), or the
    // build has failed.
    plan.afterStartTag(tokenInput(ctx, plan.element().pos(), ownTokenNames(plan)));
  }

  /**
   * Whether {@code form} has a static {@code method} that a browser reads as POST: the first
   * {@code method} attribute is {@code post}, ASCII case-insensitive and untrimmed.
   */
  public static boolean isStaticPost(Element form) {
    for (Attribute a : form.attributes()) {
      if (!a.isThwart() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "method")) {
        return a.value() != null && HtmlParser.asciiLower(a.value()).equals("post");
      }
    }
    return false;
  }

  /**
   * The literal {@code name}s of the form's own token inputs: direct children
   * {@code <input type="hidden" name="...">} with a static {@code type} ({@code hidden}, ASCII
   * case-insensitive) and a static, non-empty {@code name}, written whenever the form's start tag
   * is. So the input has no {@code t:} attribute except {@code t:value} and {@code t:id} (nothing
   * that can drop it or change its type or name), no {@code disabled} (not submitted) and no
   * {@code form} (submits another form), and the form's body is not replaced ({@code t:text},
   * {@code t:utext}, ...). The automatic input is left out when one of these names equals
   * the token's parameter name.
   */
  static List<String> ownTokenNames(ElementPlan plan) {
    List<String> names = new ArrayList<>();
    if (plan.body() != null) {
      return names;
    }
    for (Node n : plan.element().children()) {
      if (!(n instanceof Element) || !HtmlParser.asciiEqualsIgnoreCase(((Element) n).name(), "input")) {
        continue;
      }
      String type = null;
      String name = null;
      boolean always = true;
      for (Attribute a : ((Element) n).attributes()) {
        String an = HtmlParser.asciiLower(a.name());
        if (a.isThwart()) {
          always &= an.equals(Attribute.PREFIX + "value") || an.equals(Attribute.PREFIX + "id");
        } else if (an.equals("type")) {
          type = type == null ? a.value() : type;
        } else if (an.equals("name")) {
          name = name == null ? a.value() : name;
        } else if (an.equals("disabled") || an.equals("form")) {
          always = false;
        }
      }
      if (always && type != null && HtmlParser.asciiLower(type).equals("hidden") && name != null
          && !name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Checks the static {@code formaction} and {@code formmethod} of every element, since any form
   * may get the token and a {@code form} attribute or a fragment can make a button submit any
   * form: the action must be same-origin by shape, and the method {@code post} or
   * {@code dialog} (any other value, invalid ones included, submits as GET and would put the token
   * in the URL). Computed ones are checked by {@code GenericAttrProcessor}.
   */
  public static void checkSubmitter(CodegenContext ctx, ElementPlan plan) {
    Optional<ElementPlan.Attr> action = plan.attribute("formaction");
    Optional<ElementPlan.Attr> method = plan.attribute("formmethod");
    boolean staticAction = action.isPresent() && action.get() instanceof ElementPlan.StaticAttr;
    boolean staticMethod = method.isPresent() && method.get() instanceof ElementPlan.StaticAttr;
    if (staticAction) {
      Attribute a = ((ElementPlan.StaticAttr) action.get()).source();
      if (!isSameOriginStatic(a.value())) {
        ctx.diagnostics()
            .error(a.pos(), a.name() + "=\"" + a.value() + "\" is not allowed here: " + IN_TOKEN_FORM
                + ", and this URL is not same-origin; use a same-origin URL such as /items or"
                + " @{/items/{id}(id=${item.id})}, or a form of its own with a static action to the other site");
      }
    }
    if (staticMethod) {
      Attribute m = ((ElementPlan.StaticAttr) method.get()).source();
      String v = m.value() == null ? "" : HtmlParser.asciiLower(m.value());
      if (!v.equals("post") && !v.equals("dialog")) {
        ctx.diagnostics().error(m.pos(), m.name() + "=\"" + m.value() + "\" is not allowed here: " + GET_TOKEN);
      }
    }
  }

  /**
   * Checks every static {@code href} of a {@code <base>}, whether or not the template has a
   * form (a layout's applies to the forms its fragments insert): every relative action resolves
   * against it, so it must be same-origin by shape, like a static {@code action}. A computed one is
   * a same-origin link or a build error ({@code GenericAttrProcessor}). Every {@code href} is
   * checked, since a browser uses the first and the plan keeps the last.
   */
  public static void checkBase(CodegenContext ctx, Element element) {
    if (!HtmlParser.asciiEqualsIgnoreCase(element.name(), "base")) {
      return;
    }
    for (Attribute a : element.attributes()) {
      if (!a.isThwart() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "href") && !isSameOriginStatic(a.value())) {
        ctx.diagnostics()
            .error(a.pos(), a.name() + "=\"" + a.value() + "\" on <base> is not allowed: " + BASE_MOVES_URLS
                + ", and this URL is not same-origin; use a same-origin path such as /app/, or remove <base>"
                + " and write absolute URLs where another origin is meant");
      }
    }
  }

  /** Starts the reason for the {@code <base href>} errors. */
  static final String BASE_MOVES_URLS =
      "every relative URL on the page resolves against <base href>, so a POST form with a relative action would"
          + " send the CSRF token there";

  /** Starts the reason for the formaction errors. */
  static final String IN_TOKEN_FORM =
      "a submit button sends its form, CSRF token included, to its formaction";

  /** The reason for the formmethod errors. */
  static final String GET_TOKEN =
      "a formmethod other than post or dialog submits the form as GET, with its CSRF token in the URL; remove"
          + " formmethod, or give the button a form of its own with method=\"get\"";

  /** The reason for the computed {@code method} error on a {@code <form>}. */
  static final String COMPUTED_METHOD =
      "the method decides whether the form gets the CSRF token, so it must be static; write method=\"post\" or"
          + " method=\"get\", or two forms";

  /** Starts the reason for the computed action error on a POST form. */
  static final String POST_ACTION = "a POST form gets the CSRF token, which then goes to its action";

  /** Ends it. */
  static final String POST_ACTION_ELSE =
      "; omit the action to post to the page's own URL, or write a static action=\"https://...\" if another"
          + " site is meant (it gets no token)";

  /**
   * {@link Checks#isSameOriginUrl} for a static attribute value, which is written as in the source.
   * The parser decodes numeric and a few named character references; a browser decodes every named
   * one ({@code &sol;}, {@code &colon;}, {@code &Tab;}, ...). So each {@code &name;} left in
   * {@code value} may be any character: the value must pass with it removed, as {@code /} and as
   * {@code :}, which covers every way it could make the URL protocol-relative or give it a scheme
   * ({@code isSameOriginUrl} reads {@code \} as {@code /}, so {@code /} also stands for
   * {@code &bsol;}). The pattern also matches a literal {@code &} (from {@code &amp;}) before
   * letters, so a relative action with one before any {@code / ? #} ({@code action="a&amp;b"})
   * gets no token: fail-closed, and {@code /x?a&b} still passes.
   */
  private static boolean isSameOriginStatic(String value) {
    if (value == null) {
      return true;
    }
    for (String c : new String[] {"", "/", ":"}) {
      if (!Checks.isSameOriginUrl(Contexts.UNDECODED_REFERENCE.matcher(value).replaceAll(c))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The hidden input, written when the request has a token whose parameter name is none of
   * {@code ownNames}. Its holes and the optional block carry {@code form}, the {@code <form>} start
   * tag's position, so a verifier error about the token input names the form.
   */
  private static Emitted tokenInput(CodegenContext ctx, SourcePos form, List<String> ownNames) {
    String t = ctx.freshName("csrf");
    Emitted input =
        Emitted.concat(
            Emitted.text("<input type=\"hidden\"", null),
            Emitted.attributeValue(Skeleton.Escaper.ATTR, "name", form, t + ".get().parameterName()"),
            Emitted.attributeValue(Skeleton.Escaper.ATTR, "value", form, t + ".get().token()"),
            Emitted.text(" />", null));
    StringBuilder notOwn = new StringBuilder();
    for (String name : ownNames) {
      notOwn.append(" && !").append(t).append(".get().parameterName().equals(")
          .append(JavaStrings.literal(name)).append(')');
    }
    return Emitted.opt(
        Skeleton.OptKind.CSRF_TOKEN,
        form,
        "{ java.util.Optional<dev.chainguard.thwart.runtime.Forms.CsrfToken> " + t + " = ctx.forms().csrfToken(); if (" + t
            + ".isPresent()" + notOwn + ") { ",
        input,
        " } }");
  }
}
