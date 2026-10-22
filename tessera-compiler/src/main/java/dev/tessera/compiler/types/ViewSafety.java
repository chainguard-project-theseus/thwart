package dev.tessera.compiler.types;

import dev.tessera.compiler.CompilerEnv;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * The view-safe allow-list and the never-view-safe deny-list (docs/mvp-design.md).
 *
 * <p>View-safe: primitives and boxes, String/CharSequence, enums, records, {@code java.time}
 * value types, BigDecimal/BigInteger, Optional/List/Set/Collection/Iterable/Map/Map.Entry of
 * view-safe types, arrays of view-safe types, {@code LoopStatus}, {@code SafeHtml}, {@code TrustedUrl},
 * and classes annotated {@code @TemplateView}.
 *
 * <p>Never view-safe, even if annotated or registered: {@code Object} (as a declared type and
 * its methods), {@code Class}, {@code ClassLoader}, {@code java.lang.reflect.*},
 * {@code java.lang.invoke.*}, {@code Thread}, {@code ThreadGroup}, {@code Runtime},
 * {@code ProcessBuilder}, {@code Process}, {@code System}, {@code SecurityManager},
 * {@code java.io.File}, {@code java.nio.file.*}, {@code javax.script.*}, {@code jakarta.servlet.*},
 * {@code javax.servlet.*}, {@code org.springframework.context.*},
 * {@code org.springframework.beans.*}, {@code org.springframework.core.env.*},
 * {@code org.springframework.core.io.*}, {@code org.springframework.web.context.*}, and
 * {@code org.springframework.expression.*}. {@code Object} is allowed only as a
 * {@code @TemplateFunction} parameter type (input only: nothing can be called on it).
 */
public final class ViewSafety {
  private final CompilerEnv env;

  public ViewSafety(CompilerEnv env) {
    this.env = env;
  }

  public boolean isViewSafe(TypeMirror type) {
    throw new UnsupportedOperationException("TODO(#4): ViewSafety.isViewSafe");
  }

  /** Why {@code type} is never view-safe, or empty if it is not on the deny-list. */
  public Optional<String> neverViewSafeReason(TypeMirror type) {
    throw new UnsupportedOperationException("TODO(#4): ViewSafety.neverViewSafeReason " + env.basePackage());
  }
}
