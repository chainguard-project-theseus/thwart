package dev.tessera.spring.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
}
