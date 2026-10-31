package dev.tessera.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AttributesTest {

  @Test
  void appendClass() {
    assertEquals("a b", Attributes.appendClass("a", "b"));
    assertEquals("b", Attributes.appendClass(null, "b"));
    assertEquals("b", Attributes.appendClass("", "b"));
    assertEquals("a", Attributes.appendClass("a", null));
    assertEquals("a", Attributes.appendClass("a", ""));
    assertEquals("", Attributes.appendClass("", null));
    assertNull(Attributes.appendClass(null, null));
    assertNull(Attributes.appendClass(null, ""));
    assertEquals("a 1", Attributes.appendClass("a", 1));
  }

  /** #542: AngularJS reads directives from class values; such tokens are dropped, others kept. */
  @Test
  void classesDropAngularDirectiveTokens() {
    assertEquals("x", Attributes.classes("x ng-init:constructor.constructor('alert(document.domain)')()"));
    // every spelling AngularJS normalizes to a directive, and more (the rule is coarse)
    assertEquals("ok angular x-x-ng", Attributes.classes(
        "ng-bind ok NG_INIT ngShow data-ng-if x_ng-if DATA--ng-x _ng-x -ngx ng angular x-x-ng ngrok"));
    // AngularJS takes each directive name from a run of [A-Za-z0-9_-], whatever surrounds it
    assertEquals("b", Attributes.classes("a.ng-init:x b ;ng-bind (ng-show) \"ng-if\""));
    assertEquals("a b", Attributes.classes("a\tng-x\n\fb\r"));
    assertEquals("", Attributes.classes("ng-init:x"));
    // values without such a token are written as they are, always as the text that was checked
    assertEquals(" btn  btn-primary\tangular ", Attributes.classes(" btn  btn-primary\tangular "));
    assertEquals("1", Attributes.classes(1));
    Object flips =
        new Object() {
          int calls;

          @Override
          public String toString() {
            return calls++ == 0 ? "ok" : "x ng-init:alert(1)";
          }
        };
    assertEquals("ok", Attributes.classes(flips));
    assertNull(Attributes.classes(null));
  }
}
