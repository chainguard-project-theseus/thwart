package dev.tessera.runtime.fn;

import dev.tessera.runtime.TemplateFunction;
import dev.tessera.runtime.TemplateFunctions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/**
 * Built-in {@code temporals} namespace (Thymeleaf's {@code #temporals}). Always registered.
 *
 * <p>A {@code null} temporal formats as {@code null} (renders as nothing). Text fields (month and
 * day names) use {@link Locale#ENGLISH}: unlike Thymeleaf, which uses the request locale, a
 * static function cannot see the {@code RenderContext}.
 */
@TemplateFunctions("temporals")
public final class Temporals {
  private Temporals() {}

  /** Formats {@code temporal} with a {@link DateTimeFormatter} pattern such as {@code yyyy-MM-dd}. */
  @TemplateFunction
  public static String format(TemporalAccessor temporal, String pattern) {
    if (temporal == null) {
      return null;
    }
    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(temporal);
  }

  /**
   * ISO-8601 form of {@code temporal}: {@code 2024-01-05}, {@code 2024-01-05T10:15:30},
   * {@code 2024-01-05T10:15:30+01:00} (zoned values use their offset), {@code 2024-01-05T09:15:30Z}.
   *
   * @throws IllegalArgumentException for types other than the {@code java.time} value types
   */
  @TemplateFunction
  public static String formatIso(TemporalAccessor temporal) {
    return switch (temporal) {
      case null -> null;
      case ZonedDateTime z -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(z);
      case LocalDate d -> d.toString();
      case LocalDateTime dt -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dt);
      case LocalTime t -> DateTimeFormatter.ISO_LOCAL_TIME.format(t);
      case OffsetDateTime o -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(o);
      case OffsetTime o -> DateTimeFormatter.ISO_OFFSET_TIME.format(o);
      case Instant i -> i.toString();
      case YearMonth ym -> ym.toString();
      case Year y -> y.toString();
      default ->
          throw new IllegalArgumentException(
              "temporals.formatIso does not support " + temporal.getClass().getName());
    };
  }
}
