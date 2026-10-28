package dev.tessera.spring.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static void login(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void noAuthentication() {
    assertFalse(Auth.isAuthenticated());
    assertFalse(Auth.hasRole("USER"));
    assertFalse(Auth.hasAnyRole("USER", "ADMIN"));
    assertFalse(Auth.hasAuthority("PERM_X"));
    assertFalse(Auth.hasAnyAuthority("PERM_X", "ROLE_USER"));
    assertNull(Auth.name());
  }

  @Test
  void authenticatedUser() {
    login(
        new UsernamePasswordAuthenticationToken(
            "alice", "pw", AuthorityUtils.createAuthorityList("ROLE_USER")));
    assertTrue(Auth.isAuthenticated());
    assertTrue(Auth.hasRole("USER"));
    assertTrue(Auth.hasRole("ROLE_USER"));
    assertFalse(Auth.hasRole("ADMIN"));
    assertTrue(Auth.hasAnyRole("ADMIN", "USER"));
    assertFalse(Auth.hasAnyRole("ADMIN", "OPS"));
    assertFalse(Auth.hasAnyRole());
    assertEquals("alice", Auth.name());
  }

  @Test
  void roleMatchIsExactNotPrefixOrCaseInsensitive() {
    login(
        new UsernamePasswordAuthenticationToken(
            "alice", "pw", AuthorityUtils.createAuthorityList("ROLE_ADMINISTRATOR", "admin")));
    assertFalse(Auth.hasRole("ADMIN"));
    assertFalse(Auth.hasRole("admin"));
    assertFalse(Auth.hasRole(null));
  }

  @Test
  void anonymousIsNotAuthenticated() {
    login(
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    assertFalse(Auth.isAuthenticated());
    assertFalse(Auth.hasRole("USER"));
    assertEquals("anonymousUser", Auth.name());
  }

  @Test
  void unauthenticatedTokenGrantsNothing() {
    TestingAuthenticationToken token = new TestingAuthenticationToken("mallory", "pw", "ROLE_ADMIN");
    token.setAuthenticated(false);
    login(token);
    assertFalse(Auth.isAuthenticated());
    assertFalse(Auth.hasRole("ADMIN"));
  }

  @Test
  void authorityIsComparedExactlyWithoutRolePrefix() {
    login(
        new UsernamePasswordAuthenticationToken(
            "bob", "pw", AuthorityUtils.createAuthorityList("PERM_EDIT", "ROLE_USER")));
    assertTrue(Auth.hasAuthority("PERM_EDIT"));
    assertTrue(Auth.hasAuthority("ROLE_USER"));
    assertFalse(Auth.hasAuthority("USER"));
    assertFalse(Auth.hasAuthority("perm_edit"));
    assertFalse(Auth.hasAuthority("PERM"));
    assertFalse(Auth.hasAuthority("PERM_EDIT "));
    assertFalse(Auth.hasAuthority(""));
    assertFalse(Auth.hasAuthority(null));
    assertFalse(Auth.hasRole("PERM_EDIT"));
    assertTrue(Auth.hasAnyAuthority("PERM_VIEW", "PERM_EDIT"));
    assertFalse(Auth.hasAnyAuthority("PERM_VIEW", "EDIT"));
    assertFalse(Auth.hasAnyAuthority());
    assertFalse(Auth.hasAnyAuthority((String[]) null));
  }

  /** Same answers as Spring Security's own {@code hasAuthority}/{@code hasAnyAuthority}. */
  @Test
  void authorityChecksMatchSpringSecurityExpressions() {
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(
            "bob", "pw", AuthorityUtils.createAuthorityList("PERM_EDIT", "ROLE_USER", "admin"));
    login(authentication);
    // Older Spring Security lines this test runs against have neither the generic type nor the
    // replacement constructor.
    @SuppressWarnings({"deprecation", "rawtypes"})
    SecurityExpressionRoot spring = new SecurityExpressionRoot(authentication) {};
    String[] names = {"PERM_EDIT", "ROLE_USER", "USER", "admin", "ADMIN", "ROLE_admin", "PERM"};
    for (String name : names) {
      assertEquals(spring.hasAuthority(name), Auth.hasAuthority(name), name);
      assertEquals(spring.hasRole(name), Auth.hasRole(name), name);
      for (String other : names) {
        if (other.equals(name)) {
          continue; // Spring Security rejects a repeated name
        }
        assertEquals(
            spring.hasAnyAuthority(name, other),
            Auth.hasAnyAuthority(name, other),
            name + ", " + other);
      }
    }
  }

  @Test
  void anonymousHasOnlyItsOwnAuthority() {
    login(
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    assertFalse(Auth.hasAuthority("PERM_EDIT"));
    assertTrue(Auth.hasAuthority("ROLE_ANONYMOUS"));
  }

  @Test
  void unauthenticatedTokenGrantsNoAuthority() {
    TestingAuthenticationToken token =
        new TestingAuthenticationToken("mallory", "pw", "PERM_ADMIN");
    token.setAuthenticated(false);
    login(token);
    assertFalse(Auth.hasAuthority("PERM_ADMIN"));
    assertFalse(Auth.hasAnyAuthority("PERM_ADMIN"));
  }
}
