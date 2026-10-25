package dev.tessera.compiler.oracle.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Random mutations of a template for explore mode (#321): insert, delete or duplicate a token;
 * change the case of a tag name; swap quote styles. Only the body changes: the root element with
 * {@code t:params} is kept, and holes are never deleted, so a mutant still declares and uses its
 * parameters.
 */
final class Mutator {

  /** The kinds of mutation; {@link #mutate} names the ones it applied. */
  enum Op {
    INSERT,
    DELETE,
    DUPLICATE,
    TAG_CASE,
    SWAP_QUOTES
  }

  private static final Pattern ROOT =
      Pattern.compile("(<(\\w+)[^>]*\\bt:params=\"[^\"]*\"[^>]*>)(.*)(</\\2>)", Pattern.DOTALL);

  private static final Pattern TOKEN =
      Pattern.compile(
          "<!--|-->|\\[\\[.*?\\]\\]|\\[\\(.*?\\)\\]|</?[A-Za-z][^<>]*>|<[^<>]*>|&#?\\w+;|\\s+|\\w+|.",
          Pattern.DOTALL);

  private static final Pattern TAG_NAME = Pattern.compile("^(</?)([A-Za-z][A-Za-z0-9-]*)");

  private Mutator() {}

  /** The result of {@link #mutate}: the new template and the operations applied. */
  record Mutant(String template, List<Op> ops) {}

  /**
   * Applies 1-3 random mutations to the body of {@code template}. A template without a {@code
   * t:params} root is treated as a body and wrapped in {@code <div t:params="String s">}.
   */
  static Mutant mutate(String template, Random random) {
    String open;
    String body;
    String close;
    Matcher m = ROOT.matcher(template);
    if (m.matches()) {
      open = m.group(1);
      body = m.group(3);
      close = m.group(4);
    } else {
      open = "<div t:params=\"String s\">";
      body = template;
      close = "</div>";
    }
    List<String> tokens = tokens(body);
    List<Op> ops = new ArrayList<>();
    int count = 1 + random.nextInt(3);
    for (int i = 0; i < count; i++) {
      ops.add(apply(tokens, random));
    }
    return new Mutant(open + String.join("", tokens) + close, List.copyOf(ops));
  }

  static List<String> tokens(String body) {
    List<String> tokens = new ArrayList<>();
    Matcher t = TOKEN.matcher(body);
    while (t.find()) {
      tokens.add(t.group());
    }
    return tokens;
  }

  private static Op apply(List<String> tokens, Random random) {
    Op op = Op.values()[random.nextInt(Op.values().length)];
    switch (op) {
      case DELETE -> {
        List<Integer> deletable = indices(tokens, t -> !isHole(t));
        if (!deletable.isEmpty()) {
          tokens.remove((int) deletable.get(random.nextInt(deletable.size())));
          return op;
        }
      }
      case DUPLICATE -> {
        if (!tokens.isEmpty()) {
          int i = random.nextInt(tokens.size());
          tokens.add(i, tokens.get(i));
          return op;
        }
      }
      case TAG_CASE -> {
        List<Integer> tags = indices(tokens, t -> TAG_NAME.matcher(t).find());
        if (!tags.isEmpty()) {
          int i = tags.get(random.nextInt(tags.size()));
          Matcher n = TAG_NAME.matcher(tokens.get(i));
          n.find();
          String name = n.group(2);
          String changed = random.nextBoolean() ? name.toUpperCase(Locale.ROOT) : alternate(name);
          if (changed.equals(name)) {
            changed = name.toLowerCase(Locale.ROOT);
          }
          tokens.set(i, n.group(1) + changed + tokens.get(i).substring(n.end()));
          return op;
        }
      }
      case SWAP_QUOTES -> {
        List<Integer> quoted = indices(tokens, t -> t.startsWith("<") && (t.indexOf('"') >= 0 || t.indexOf('\'') >= 0));
        if (!quoted.isEmpty()) {
          int i = quoted.get(random.nextInt(quoted.size()));
          tokens.set(i, swapQuotes(tokens.get(i)));
          return op;
        }
      }
      case INSERT -> {
        // Falls through to the insert below.
      }
    }
    Interference[] values = Interference.values();
    Interference inserted = values[1 + random.nextInt(values.length - 1)];
    tokens.add(random.nextInt(tokens.size() + 1), inserted.prefix());
    return Op.INSERT;
  }

  private static boolean isHole(String token) {
    return token.startsWith("[[") || token.startsWith("[(") || token.contains("t:");
  }

  private static List<Integer> indices(List<String> tokens, java.util.function.Predicate<String> test) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      if (test.test(tokens.get(i))) {
        out.add(i);
      }
    }
    return out;
  }

  private static String alternate(String name) {
    StringBuilder sb = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      sb.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
    }
    return sb.toString();
  }

  private static String swapQuotes(String tag) {
    StringBuilder sb = new StringBuilder(tag.length());
    for (int i = 0; i < tag.length(); i++) {
      char c = tag.charAt(i);
      sb.append(c == '"' ? '\'' : c == '\'' ? '"' : c);
    }
    return sb.toString();
  }
}
