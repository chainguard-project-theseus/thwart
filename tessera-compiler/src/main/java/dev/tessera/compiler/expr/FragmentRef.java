package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;
import java.util.List;

/**
 * {@code ~{template :: selector(args)}} (the {@code ~{}} wrapper is optional in
 * {@code t:insert}/{@code t:replace}).
 *
 * @param template view name of another template, or null for {@code ~{:: x}} / {@code ~{this :: x}}
 * @param selector fragment name, or an element name when used as a Fragment value ({@code ~{::body}})
 * @param args arguments; all named or all positional
 */
public record FragmentRef(String template, String selector, List<Arg> args, SourcePos pos) {
  public FragmentRef {
    args = List.copyOf(args);
  }

  /** {@code name} is null for positional arguments. */
  public record Arg(String name, Expr value, SourcePos pos) {}
}
