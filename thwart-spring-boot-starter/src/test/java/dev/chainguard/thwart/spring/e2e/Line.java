package dev.chainguard.thwart.spring.e2e;

import dev.chainguard.thwart.runtime.TemplateView;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** A {@link Cart} line; {@code due} is shown through its {@code @DateTimeFormat}. */
@TemplateView
public class Line {
  private String name;

  @DateTimeFormat(pattern = "dd.MM.yyyy")
  private LocalDate due;

  public Line() {}

  public Line(String name, LocalDate due) {
    this.name = name;
    this.due = due;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public LocalDate getDue() {
    return due;
  }

  public void setDue(LocalDate due) {
    this.due = due;
  }
}
