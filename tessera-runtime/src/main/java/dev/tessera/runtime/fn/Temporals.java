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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Built-in {@code temporals} namespace (Thymeleaf's {@code #temporals}). Always registered.
 *
 * <p>A {@code null} temporal formats as {@code null} (renders as nothing). Text fields (month and
 * day names) use {@link Locale#ENGLISH}: unlike Thymeleaf, which uses the request locale, a
 * static function cannot see the {@code RenderContext}.
 */
@TemplateFunctions("temporals")
public final class Temporals {
  // Collisions replace one entry; dynamic patterns can never grow the cache without bound.
  private static final AtomicReferenceArray<PatternFormat> FORMATS = new AtomicReferenceArray<>(32);

  private Temporals() {}

  /** Formats {@code temporal} with a {@link DateTimeFormatter} pattern such as {@code yyyy-MM-dd}. */
  @TemplateFunction
  public static String format(TemporalAccessor temporal, String pattern) {
    if (temporal == null) {
      return null;
    }
    return formatter(pattern).format(temporal);
  }

  private static DateTimeFormatter formatter(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    // Also bound the size of each retained pattern and its compiled formatter.
    if (pattern.length() > 128) {
      return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    }
    int slot = pattern.hashCode() & (FORMATS.length() - 1);
    PatternFormat cached = FORMATS.get(slot);
    if (cached != null && cached.pattern.equals(pattern)) {
      return cached.format;
    }
    DateTimeFormatter format = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    FORMATS.set(slot, new PatternFormat(pattern, format));
    return format;
  }

  private static final class PatternFormat {
    final String pattern;
    final DateTimeFormatter format;

    PatternFormat(String pattern, DateTimeFormatter format) {
      this.pattern = pattern;
      this.format = format;
    }
  }

  /**
   * ISO-8601 form of {@code temporal}: {@code 2024-01-05}, {@code 2024-01-05T10:15:30},
   * {@code 2024-01-05T10:15:30+01:00} (zoned values use their offset), {@code 2024-01-05T09:15:30Z}.
   *
   * @throws IllegalArgumentException for types other than the {@code java.time} value types
   */
  @TemplateFunction
  public static String formatIso(TemporalAccessor temporal) {
    if (temporal == null) {
      return null;
    }
    if (temporal instanceof ZonedDateTime) {
      return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(temporal);
    }
    if (temporal instanceof LocalDate
        || temporal instanceof Instant
        || temporal instanceof YearMonth
        || temporal instanceof Year) {
      return temporal.toString();
    }
    if (temporal instanceof LocalDateTime) {
      return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(temporal);
    }
    if (temporal instanceof LocalTime) {
      return DateTimeFormatter.ISO_LOCAL_TIME.format(temporal);
    }
    if (temporal instanceof OffsetDateTime) {
      return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(temporal);
    }
    if (temporal instanceof OffsetTime) {
      return DateTimeFormatter.ISO_OFFSET_TIME.format(temporal);
    }
    throw new IllegalArgumentException(
        "temporals.formatIso does not support " + temporal.getClass().getName());
  }
}
