package dev.tessera.spring.fn;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import org.springframework.util.ClassUtils;

/**
 * {@code auth} namespace over Spring Security's {@code SecurityContextHolder} (Thymeleaf's
 * {@code sec:authorize}/{@code #authorization}). Spring Security is optional: without it every
 * check is {@code false} and {@link #name()} is {@code null}.
 */
@TemplateFunctions("auth")
public final class Auth {
  private static final boolean SECURITY_PRESENT =
      ClassUtils.isPresent(
          "org.springframework.security.core.context.SecurityContextHolder",
          Auth.class.getClassLoader());

  private Auth() {}

  /** True for a non-anonymous, authenticated user (Spring Security's {@code isAuthenticated()}). */
  @TemplateFunction
  public static boolean isAuthenticated() {
    return SECURITY_PRESENT && Security.isAuthenticated();
  }

  /**
   * True if the current (authenticated) user has {@code role}; {@code ROLE_} is prepended unless
   * present.
   */
  @TemplateFunction
  public static boolean hasRole(String role) {
    return hasAnyRole(role);
  }

  /** True if the current user has any of {@code roles}; {@code ROLE_} is prepended unless present. */
  @TemplateFunction
  public static boolean hasAnyRole(String... roles) {
    return SECURITY_PRESENT && Security.hasAny("ROLE_", roles);
  }

  /**
   * True if the current (authenticated) user has {@code authority}, compared exactly: no {@code
   * ROLE_} prefix is added (Spring Security's {@code hasAuthority}).
   */
  @TemplateFunction
  public static boolean hasAuthority(String authority) {
    return hasAnyAuthority(authority);
  }

  /** True if the current user has any of {@code authorities}, each compared exactly. */
  @TemplateFunction
  public static boolean hasAnyAuthority(String... authorities) {
    return SECURITY_PRESENT && Security.hasAny("", authorities);
  }

  /** Name of the current authentication (including the anonymous user), or {@code null} if none. */
  @TemplateFunction
  public static String name() {
    return SECURITY_PRESENT ? Security.name() : null;
  }

  /** Holds the Spring Security references so they load only when Security is on the classpath. */
  private static final class Security {
    private static final org.springframework.security.authentication.AuthenticationTrustResolver
        TRUST =
            new org.springframework.security.authentication.AuthenticationTrustResolverImpl();

    static org.springframework.security.core.Authentication current() {
      return org.springframework.security.core.context.SecurityContextHolder.getContext()
          .getAuthentication();
    }

    static boolean isAuthenticated() {
      org.springframework.security.core.Authentication authentication = current();
      return authentication != null
          && authentication.isAuthenticated()
          && !TRUST.isAnonymous(authentication);
    }

    /** True if an authority equals one of {@code names}, {@code prefix} added unless present. */
    static boolean hasAny(String prefix, String... names) {
      org.springframework.security.core.Authentication authentication = current();
      if (authentication == null || !authentication.isAuthenticated() || names == null) {
        return false;
      }
      for (org.springframework.security.core.GrantedAuthority granted :
          authentication.getAuthorities()) {
        String authority = granted.getAuthority();
        for (String name : names) {
          if (name != null
              && authority != null
              && authority.equals(name.startsWith(prefix) ? name : prefix + name)) {
            return true;
          }
        }
      }
      return false;
    }

    static String name() {
      org.springframework.security.core.Authentication authentication = current();
      return authentication == null ? null : authentication.getName();
    }
  }
}
