package dev.chainguard.thwart.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class ResourceBundleMessagesTest {
  private static final String BASE = "dev.chainguard.thwart.runtime.testmessages";

  @Test
  void plainMessage() {
    assertEquals("Welcome", new ResourceBundleMessages(BASE, Locale.ENGLISH).get("welcome"));
  }

  @Test
  void localizedMessageWithBaseFallback() {
    Messages de = new ResourceBundleMessages(BASE, Locale.GERMAN);
    assertEquals("Willkommen", de.get("welcome"));
    assertEquals("only in base", de.get("baseOnly"));
  }

  @Test
  void argumentsFormattedWithMessageFormat() {
    Messages m = new ResourceBundleMessages(BASE, Locale.ENGLISH);
    assertEquals("Hello, Ann! You have 3 pets.", m.get("greeting", "Ann", 3));
  }

  @Test
  void noArgumentsMeansNoMessageFormat() {
    assertEquals("it's {0}", new ResourceBundleMessages(BASE, Locale.ENGLISH).get("apostrophe"));
  }

  @Test
  void argumentsAreNotReinterpretedAsPatterns() {
    Messages m = new ResourceBundleMessages(BASE, Locale.ENGLISH);
    assertEquals("Hello, {1}! You have <b> pets.", m.get("greeting", "{1}", "<b>"));
  }

  @Test
  void missingKeyRendersMarker() {
    assertEquals("??nope_en_US??", new ResourceBundleMessages(BASE, Locale.US).get("nope"));
  }

  @Test
  void missingBundleRendersMarker() {
    assertEquals("??welcome_fr??", new ResourceBundleMessages("no.such.bundle", Locale.FRENCH).get("welcome"));
  }
}
