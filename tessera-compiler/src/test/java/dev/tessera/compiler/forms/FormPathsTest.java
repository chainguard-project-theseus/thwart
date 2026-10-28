package dev.tessera.compiler.forms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tessera.compiler.SourcePos;
import dev.tessera.compiler.expr.Expr;
import org.junit.jupiter.api.Test;

class FormPathsTest {

  @Test
  void acceptsAsciiIdentifiers() {
    for (String s : new String[] {"a", "Z", "_", "lastName", "_x1", "a_b_9"}) {
      assertTrue(FormPaths.isIdentifier(s), s);
    }
  }

  @Test
  void rejectsEverythingElse() {
    // Non-ASCII letters and digits, '$' (internal names such as select$value), and other
    // punctuation are all rejected.
    for (String s : new String[] {"", "1a", "a-b", "a.b", "a b", "select$value", "$x", "é", "aé", "a١", "Ж"}) {
      assertFalse(FormPaths.isIdentifier(s), s);
    }
  }

  @Test
  void idDropsBracketsLikeSpring() {
    assertEquals("items0.name", FormPaths.idOf("items[0].name"));
    assertEquals("a.b", FormPaths.idOf("a.b"));
  }

  @Test
  void parsesLiteralPathsWithIntIndexes() {
    SourcePos pos = new SourcePos("f.html", 1, 1);
    for (String s : new String[] {"a", "a.b", "items[0].name", "m[12][3]", "a[999999999]", "m[x]", "m[k_1].v"}) {
      assertNotNull(FormPaths.parseLiteral(s, pos), s);
    }
    Expr e = FormPaths.parseLiteral("items[7].name", pos);
    Expr.Index index = (Expr.Index) ((Expr.Property) e).target();
    assertEquals(7, ((Expr.Literal) index.index()).value());
    Expr.Index key = (Expr.Index) FormPaths.parseLiteral("m[x]", pos);
    assertEquals("x", ((Expr.Literal) key.index()).value());
    for (String s : new String[] {"", "a.", "[0]", "a[]", "a[01]", "a[-1]", "a['k']", "a[a b]", "a[a.b]", "a[0", "a[0]x", "a[0]]",
        "a[1000000000]", "a[ 0]", "a.[0]"}) {
      assertNull(FormPaths.parseLiteral(s, pos), s);
    }
  }
}
