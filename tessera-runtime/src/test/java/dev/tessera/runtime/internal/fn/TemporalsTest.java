package dev.tessera.runtime.internal.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TemporalsTest {
  private static final LocalDateTime DT = LocalDateTime.of(2024, 1, 5, 10, 15, 30);

  @Test
  void format() {
    assertEquals("2024-01-05", Temporals.format(Locale.ROOT, LocalDate.of(2024, 1, 5), "yyyy-MM-dd"));
    assertEquals("5 Jan 2024 Fri", Temporals.format(Locale.ROOT, LocalDate.of(2024, 1, 5), "d MMM yyyy EEE"));
    assertEquals("10:15", Temporals.format(Locale.ROOT, DT, "HH:mm"));
    assertNull(Temporals.format(Locale.ROOT, null, "yyyy"));
  }

  @Test
  void formatUsesTheLocale() {
    LocalDate d = LocalDate.of(2024, 3, 5);
    assertEquals("5. März 2024, Dienstag", Temporals.format(Locale.GERMANY, d, "d. MMMM yyyy, EEEE"));
    assertEquals("5 March 2024, Tuesday", Temporals.format(Locale.UK, d, "d MMMM yyyy, EEEE"));
    assertEquals("5 March 2024", Temporals.format(Locale.ROOT, d, "d MMMM yyyy"));
    // The cache keys on the locale as well as the pattern.
    for (int i = 0; i < 3; i++) {
      assertEquals("März", Temporals.format(Locale.GERMAN, d, "MMMM"));
      assertEquals("March", Temporals.format(Locale.ENGLISH, d, "MMMM"));
    }
  }

  @Test
  void formatRejectsFieldsTheValueLacks() {
    assertThrows(RuntimeException.class, () -> Temporals.format(Locale.ROOT, LocalDate.of(2024, 1, 5), "HH:mm"));
    assertThrows(IllegalArgumentException.class, () -> Temporals.format(Locale.ROOT, DT, "{bad"));
  }

  @Test
  void cacheCollisionsAndLongPatternsPreserveFormatting() {
    // These literals have identical String hashes but require different formatted output.
    String first = "yyyy-MM-dd 'Aa'";
    String second = "yyyy-MM-dd 'BB'";
    assertEquals(first.hashCode(), second.hashCode());
    String longPattern = "yyyy '" + "literal".repeat(40) + "'";
    for (int i = 0; i < 50; i++) {
      for (String pattern : List.of(first, second, longPattern, "", "d MMM yyyy EEE")) {
        assertEquals(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(DT),
            Temporals.format(Locale.ROOT, DT, pattern));
      }
    }
    assertThrows(NullPointerException.class, () -> Temporals.format(Locale.ROOT, DT, null));
    assertNull(Temporals.format(Locale.ROOT, null, null));
    assertNull(Temporals.format(Locale.ROOT, null, "{bad"));
    // A failed pattern must not poison a previously cached valid formatter.
    assertThrows(IllegalArgumentException.class, () -> Temporals.format(Locale.ROOT, DT, "{bad"));
    assertEquals("2024-01-05 Aa", Temporals.format(Locale.ROOT, DT, first));
  }

  @Test
  void cachedFormattersRemainIsolatedAcrossThreads() throws Exception {
    List<Callable<Void>> calls = new ArrayList<>();
    for (int task = 0; task < 16; task++) {
      final LocalDateTime date = DT.plusDays(task);
      final String pattern = task % 2 == 0 ? "yyyy-MM-dd 'Aa'" : "yyyy-MM-dd 'BB'";
      final String expected = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(date);
      calls.add(() -> {
        for (int i = 0; i < 500; i++) {
          assertEquals(expected, Temporals.format(Locale.ROOT, date, pattern));
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(calls)) {
        result.get();
      }
    }
  }

  @Test
  void formatIso() {
    assertEquals("2024-01-05", Temporals.formatIso(LocalDate.of(2024, 1, 5)));
    assertEquals("2024-01-05T10:15:30", Temporals.formatIso(DT));
    assertEquals("10:15:30", Temporals.formatIso(LocalTime.of(10, 15, 30)));
    assertEquals("2024-01-05T10:15:30+01:00", Temporals.formatIso(OffsetDateTime.of(DT, ZoneOffset.ofHours(1))));
    assertEquals("2024-01-05T10:15:30+01:00", Temporals.formatIso(ZonedDateTime.of(DT, ZoneId.of("Europe/Berlin"))));
    assertEquals("10:15:30Z", Temporals.formatIso(OffsetTime.of(10, 15, 30, 0, ZoneOffset.UTC)));
    assertEquals("2024-01-05T09:15:30Z", Temporals.formatIso(Instant.parse("2024-01-05T09:15:30Z")));
    assertEquals("2024-01", Temporals.formatIso(YearMonth.of(2024, 1)));
    assertEquals("2024", Temporals.formatIso(Year.of(2024)));
    assertNull(Temporals.formatIso(null));
    assertThrows(IllegalArgumentException.class, () -> Temporals.formatIso(DayOfWeek.MONDAY));
  }
}
