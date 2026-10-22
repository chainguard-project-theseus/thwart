package dev.tessera.runtime.fn;

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
import org.junit.jupiter.api.Test;

class TemporalsTest {
  private static final LocalDateTime DT = LocalDateTime.of(2024, 1, 5, 10, 15, 30);

  @Test
  void format() {
    assertEquals("2024-01-05", Temporals.format(LocalDate.of(2024, 1, 5), "yyyy-MM-dd"));
    assertEquals("5 Jan 2024 Fri", Temporals.format(LocalDate.of(2024, 1, 5), "d MMM yyyy EEE"));
    assertEquals("10:15", Temporals.format(DT, "HH:mm"));
    assertNull(Temporals.format(null, "yyyy"));
  }

  @Test
  void formatRejectsFieldsTheValueLacks() {
    assertThrows(RuntimeException.class, () -> Temporals.format(LocalDate.of(2024, 1, 5), "HH:mm"));
    assertThrows(IllegalArgumentException.class, () -> Temporals.format(DT, "{bad"));
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
