package dev.tessera.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/** An {@link Output} over a {@link Writer}; IO errors are rethrown as {@link UncheckedIOException}. */
public final class WriterOutput implements Output {
  private final Writer writer;

  public WriterOutput(Writer writer) {
    this.writer = writer;
  }

  @Override
  public void write(String s) {
    try {
      writer.write(s);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void write(char c) {
    try {
      writer.write(c);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
