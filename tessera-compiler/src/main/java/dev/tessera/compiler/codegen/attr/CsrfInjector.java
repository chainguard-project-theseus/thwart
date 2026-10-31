package dev.tessera.compiler.codegen.attr;

import dev.tessera.compiler.JavaStrings;
import dev.tessera.compiler.codegen.CodegenContext;
import dev.tessera.compiler.codegen.ElementPlan;
import dev.tessera.compiler.codegen.Emitted;
import dev.tessera.compiler.html.Contexts;
import dev.tessera.compiler.html.HtmlParser;
import dev.tessera.compiler.model.Attribute;
import dev.tessera.compiler.model.Element;
import dev.tessera.compiler.model.Node;
import dev.tessera.compiler.verify.Skeleton;
import dev.tessera.runtime.Escapers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The CSRF token, by a closed shape (#285, #314, #350, #386, #498): the compiler adds a hidden input
 * with {@code ctx.forms().csrfToken()} after the start tag of a {@code <form>} only when it can see,
 * at build time, that the form posts to the page's origin. Everything a browser or htmx could use
 * to send that form elsewhere is held to the same rule, on every element, so no rule depends on
 * which form owns a button.
 *
 * <ul>
 *   <li>The method is a static {@code method="post"} (ASCII case-insensitive, untrimmed, as a
 *       browser reads it). Any other static value submits as GET and gets no token. A computed
 *       method on a {@code <form>} is a build error ({@code GenericAttrProcessor}).
 *   <li>The action is absent, a static URL same-origin by shape ({@link Escapers#isSameOriginUrl}),
 *       or, computed ({@code t:action}, {@code t:attr="action=..."}), a link with a same-origin
 *       literal path, re-checked when rendered ({@code GenericAttrProcessor}); any other computed
 *       action of a static POST form is a build error. A static action on another origin is the
 *       author's visible choice: no token.
 *   <li>Every {@code formaction} and {@code formmethod}, in any form or none, is checked as if its
 *       form got the token: {@link #checkSubmitter} and {@code GenericAttrProcessor}.
 *   <li>Every {@code <base href>} and static htmx request URL ({@code hx-post}, ...) is same-origin by
 *       shape: {@link #checkStaticUrls}; computed ones are same-origin links already.
 *   <li>A nested {@code <form>}, also through fragments, is a verifier {@code PARSE_ERROR}, and
 *       {@code <form/>} is an error here.
 * </ul>
 *
 * <p>A form that writes its own token input (#225) keeps it and gets no second one: a direct child
 * {@code <input type="hidden">} whose literal {@code name} equals the token's parameter name at
 * render time ({@link #ownTokenNames}).
 */
public final class CsrfInjector {
  /** A named character reference the parser did not decode (a browser may; see below). */
  private static final Pattern UNDECODED_REFERENCE = Pattern.compile("&[A-Za-z][A-Za-z0-9]*;?");

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
    // A computed action here is a same-origin link (GenericAttrProcessor), or the build has failed.
    plan.afterStartTag(tokenInput(ctx, ownTokenNames(plan)));
  }

  /**
   * Whether {@code form} has a static {@code method} that a browser reads as POST: the first
   * {@code method} attribute is {@code post}, ASCII case-insensitive and untrimmed.
   */
  public static boolean isStaticPost(Element form) {
    for (Attribute a : form.attributes()) {
      if (!a.isTessera() && HtmlParser.asciiEqualsIgnoreCase(a.name(), "method")) {
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
   * {@code t:remove="body"}, ...). The automatic input is left out when one of these names equals
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
        if (a.isTessera()) {
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
   * form (#314): the action must be same-origin by shape, and the method {@code post} or
   * {@code dialog} (any other value, invalid ones included, submits as GET and would put the token
   * in the URL). Computed ones are checked by {@code GenericAttrProcessor}.
   */
  public static void checkSubmitter(CodegenContext ctx, ElementPlan plan) {
    Optional<ElementPlan.Attr> action = plan.attribute("formaction");
    Optional<ElementPlan.Attr> method = plan.attribute("formmethod");
    boolean staticAction = action.isPresent() && action.get() instanceof ElementPlan.StaticAttr;
    boolean staticMethod = method.isPresent() && method.get() instanceof ElementPlan.StaticAttr;
    if (!staticAction && !staticMethod) {
      return;
    }
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
   * Checks the static URLs that can send a form's inputs, token included, elsewhere, on every
   * element, whether or not the template has a form (a layout's applies to the forms its fragments
   * insert): a {@code <base href>} (#350), against which every relative action resolves, and an
   * htmx request URL ({@code hx-post}, ...; #311), to which htmx sends the enclosing form. Each
   * must be same-origin by shape, like a static {@code action}. Computed ones are same-origin links
   * ({@code GenericAttrProcessor}). Every such attribute is checked, since a browser uses the
   * first and the plan keeps the last.
   */
  public static void checkStaticUrls(CodegenContext ctx, Element element) {
    boolean base = HtmlParser.asciiEqualsIgnoreCase(element.name(), "base");
    for (Attribute a : element.attributes()) {
      if (a.isTessera() || isSameOriginStatic(a.value())) {
        continue;
      }
      if (base && HtmlParser.asciiEqualsIgnoreCase(a.name(), "href")) {
        ctx.diagnostics()
            .error(a.pos(), a.name() + "=\"" + a.value() + "\" on <base> is not allowed: " + BASE_MOVES_URLS
                + ", and this URL is not same-origin; use a same-origin path such as /app/, or remove <base>"
                + " and write absolute URLs where another origin is meant");
      } else if (Contexts.isHtmxRequestUrl(a.name())) {
        ctx.diagnostics()
            .error(a.pos(), a.name() + "=\"" + a.value() + "\" is not allowed: htmx sends the enclosing form, CSRF"
                + " token included, to this URL, and it is not same-origin; use a same-origin path such as /items");
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
   * {@link Escapers#isSameOriginUrl} for a static attribute value, which is written as in the source.
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
      if (!Escapers.isSameOriginUrl(UNDECODED_REFERENCE.matcher(value).replaceAll(c))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The hidden input, written when the request has a token whose parameter name is none of
   * {@code ownNames} (made up: no source position).
   */
  private static Emitted tokenInput(CodegenContext ctx, List<String> ownNames) {
    String t = ctx.freshName("csrf");
    Emitted input =
        Emitted.concat(
            Emitted.text("<input type=\"hidden\" name=\"", null),
            Emitted.code(" "),
            Emitted.hole(Skeleton.Escaper.ATTR, "input", "name", null, t + ".get().parameterName()"),
            Emitted.code(" "),
            Emitted.text("\" value=\"", null),
            Emitted.code(" "),
            Emitted.hole(Skeleton.Escaper.ATTR, "input", "value", null, t + ".get().token()"),
            Emitted.code(" "),
            Emitted.text("\" />", null));
    StringBuilder notOwn = new StringBuilder();
    for (String name : ownNames) {
      notOwn.append(" && !").append(t).append(".get().parameterName().equals(")
          .append(JavaStrings.literal(name)).append(')');
    }
    return Emitted.opt(
        Skeleton.OptKind.CSRF_TOKEN,
        null,
        "{ java.util.Optional<dev.tessera.runtime.Forms.CsrfToken> " + t + " = ctx.forms().csrfToken(); if (" + t
            + ".isPresent()" + notOwn + ") { ",
        input,
        " } }");
  }
}
