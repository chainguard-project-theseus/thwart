package dev.chainguard.thwart.compiler.expr;

import dev.chainguard.thwart.compiler.Backports;
import dev.chainguard.thwart.compiler.DiagnosticSink;
import dev.chainguard.thwart.compiler.JavaStrings;
import dev.chainguard.thwart.compiler.SourcePos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lexer and recursive-descent parser for every micro-syntax inside {@code t:} attributes.
 * {@code start} is the position of the first character of {@code text} (usually
 * {@code Attribute.valuePos()}); node positions are computed with {@link SourcePos#advance}.
 * A surrounding {@code ${...}} is accepted and ignored everywhere an expression is expected.
 * On a syntax error each method reports a diagnostic naming what was expected and returns
 * {@code null}.
 *
 * <p>Rejected with a targeted message (these are SSTI vectors in Thymeleaf): {@code T(...)},
 * {@code new}, {@code __...__} preprocessing, {@code @bean} references, assignment, lambdas,
 * projection/selection ({@code .![]}, {@code .?[]}), and dynamic message keys or link paths.
 * A bare call {@code fn(x)} without a namespace is also rejected: functions are reached only as
 * {@code ns.fn(x)} or {@code #ns.fn(x)}.
 */
public final class ExpressionParser {

  /** Guards javac against a stack overflow on pathologically nested input. */
  private static final int MAX_DEPTH = 200;

  /** A value expression ({@code t:text}, {@code t:if}, {@code t:href}, ...). */
  public Expr parseExpression(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      Expr e = p.expr();
      p.end();
      return e;
    });
  }

  /** {@code t:each}: {@code o : orders} or {@code o, stat : orders}. */
  public EachSpec parseEach(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      p.ws();
      int at = p.i;
      String var = p.ident("a loop variable name");
      String stat = p.eat(",") ? p.ident("a status variable name after ','") : null;
      p.expect(":", "':' between the loop variable and the iterable (e.g. 'item : items')");
      Expr iterable = p.expr();
      p.end();
      return new EachSpec(var, stat, iterable, p.pos(at));
    });
  }

  /** {@code t:with}: {@code a=expr, b=expr}. */
  public List<Assignment> parseAssignments(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      List<Assignment> out = new ArrayList<>();
      do {
        p.ws();
        int at = p.i;
        String name = p.attributeName();
        p.expect("=", "'=' after '" + name + "'");
        out.add(new Assignment(name, p.expr(), p.pos(at)));
      } while (p.eat(","));
      p.end();
      return Backports.listCopy(out);
    });
  }

  /** {@code t:params}: {@code OwnerView owner, List<PetView> pets}. Empty text yields an empty list. */
  public List<ParamDecl> parseParams(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      p.ws();
      List<ParamDecl> params = p.atEnd() ? Backports.listOf() : p.paramList();
      p.end();
      return params;
    });
  }

  /** {@code t:import}: comma-separated qualified names or {@code package.*}. */
  public List<String> parseImports(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      List<String> out = new ArrayList<>();
      p.ws();
      if (p.atEnd()) {
        return Backports.<String>listOf();
      }
      do {
        String name = p.qualifiedName("a package or class name");
        if (p.at(".*")) {
          p.i += 2;
          name += ".*";
        }
        out.add(name);
      } while (p.eat(","));
      p.end();
      return Backports.listCopy(out);
    });
  }

  /** {@code t:fragment}: {@code name} or {@code name(Type a, Type b)}. */
  public FragmentSignature parseFragmentSignature(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      p.ws();
      int at = p.i;
      String name = p.fragmentName("a fragment name");
      List<ParamDecl> params = Backports.listOf();
      if (p.eat("(") && !p.eat(")")) {
        params = p.paramList();
        p.expect(")", "',' or ')' in the fragment parameter list");
      }
      p.end();
      return new FragmentSignature(name, params, p.pos(at));
    });
  }

  /**
   * {@code t:insert}/{@code t:replace}: {@code ~{tpl :: name(args)}}, {@code tpl :: name},
   * {@code ~{:: name}}, {@code ~{this :: name}}. Always contains {@code ::}; a value without
   * {@code ::} (a Fragment-typed variable such as {@code template}) is a plain expression and
   * callers parse it with {@link #parseExpression}.
   */
  public FragmentRef parseFragmentRef(String text, SourcePos start, DiagnosticSink diagnostics) {
    return run(text, start, diagnostics, p -> {
      p.ws();
      FragmentRef ref = p.at("~{") ? p.fragment() : p.fragmentBody(p.i);
      p.end();
      return ref;
    });
  }

  private static <T> T run(String text, SourcePos start, DiagnosticSink diagnostics, Function<Parser, T> body) {
    Parser p = new Parser(text == null ? "" : text, start);
    try {
      p.rejectPreprocessing();
      return body.apply(p);
    } catch (ParseError e) {
      diagnostics.error(e.pos, e.getMessage());
      return null;
    }
  }

  private static final class ParseError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    // Caught inside run() and never serialized; SourcePos is not Serializable.
    final transient SourcePos pos;

    ParseError(SourcePos pos, String message) {
      super(message, null, false, false);
      this.pos = pos;
    }
  }

  private static final class Parser {
    final String s;
    final SourcePos start;
    int i;
    int depth;

    Parser(String s, SourcePos start) {
      this.s = s;
      this.start = start;
    }

    // ---- scanning helpers ----

    SourcePos pos(int at) {
      return start.advance(s, at);
    }

    ParseError error(int at, String message) {
      return new ParseError(pos(at), message);
    }

    boolean atEnd() {
      return i >= s.length();
    }

    char peek() {
      return peekAt(0);
    }

    char peekAt(int k) {
      int j = i + k;
      return j < s.length() ? s.charAt(j) : '\0';
    }

    void ws() {
      while (!atEnd() && JavaStrings.isJavaWhitespace(s.charAt(i))) {
        i++;
      }
    }

    boolean at(String t) {
      return s.startsWith(t, i);
    }

    boolean eat(String t) {
      ws();
      if (at(t)) {
        i += t.length();
        return true;
      }
      return false;
    }

    boolean eatWord(String w) {
      ws();
      if (at(w) && !identPart(peekAt(w.length()))) {
        i += w.length();
        return true;
      }
      return false;
    }

    void expect(String t, String what) {
      if (!eat(t)) {
        throw error(i, "expected " + what + " but " + found());
      }
    }

    String found() {
      return atEnd() ? "reached the end" : "found '" + peek() + "'";
    }

    void end() {
      ws();
      if (!atEnd()) {
        throw error(i, "unexpected '" + peek() + "': expected the end of the expression (check for a missing"
            + " operator or an unbalanced bracket or quote)");
      }
    }

    static boolean identStart(char c) {
      return JavaStrings.isJavaLetter(c) || c == '_';
    }

    static boolean identPart(char c) {
      return JavaStrings.isJavaLetterOrDigit(c) || c == '_';
    }

    static boolean isIdentifier(String s) {
      if (s.isEmpty() || !identStart(s.charAt(0))) {
        return false;
      }
      for (int k = 1; k < s.length(); k++) {
        if (!identPart(s.charAt(k))) {
          return false;
        }
      }
      return true;
    }

    String identRaw() {
      int b = i;
      while (!atEnd() && identPart(peek())) {
        i++;
      }
      return s.substring(b, i);
    }

    String ident(String what) {
      ws();
      if (!identStart(peek())) {
        throw error(i, "expected " + what + " but " + found());
      }
      return identRaw();
    }

    /** Runs {@code body} one nesting level deeper, failing with a diagnostic past {@link #MAX_DEPTH}. */
    private <T> T nested(Supplier<T> body) {
      if (++depth > MAX_DEPTH) {
        throw error(i, "expression is nested more than " + MAX_DEPTH + " levels deep: simplify it or compute the value in"
            + " Java");
      }
      try {
        return body.get();
      } finally {
        depth--;
      }
    }

    /**
     * Rejects Thymeleaf's {@code __...__} preprocessing so a migrated template fails loudly
     * instead of silently changing meaning. Thwart never preprocesses, so {@code __} is inert
     * text inside a {@code '...'} string or the text of a {@code |...|} substitution (BEM class
     * names such as {@code 'card__title'}); there only {@code __} directly followed by an
     * expression opener ({@code ${ *{ #{ @{ ~{}) and closed by a later {@code __}, which
     * Thymeleaf would preprocess, is rejected. Outside literals any {@code __} is rejected. This
     * scan only adds errors, so a misjudged literal boundary can never make anything compile.
     */
    void rejectPreprocessing() {
      final int code = 0, string = 1, text = 2;
      int[] modes = new int[s.length() + 1];
      int[] braces = new int[s.length() + 1];
      int top = 0;
      modes[0] = code;
      for (int k = 0; k < s.length(); k++) {
        char c = s.charAt(k);
        int mode = modes[top];
        if (mode == string) {
          if (c == '\\' && k + 1 < s.length() && (s.charAt(k + 1) == '\'' || s.charAt(k + 1) == '\\')) {
            k++;
          } else if (c == '\'') {
            top--;
          } else if (s.startsWith("__", k) && preprocessesAt(k)) {
            throw preprocessing(k);
          }
        } else if (mode == text) {
          if (c == '|') {
            top--;
          } else if (s.startsWith("__", k) && preprocessesAt(k)) {
            throw preprocessing(k);
          } else if ((c == '$' || c == '*' || c == '#') && k + 1 < s.length() && s.charAt(k + 1) == '{') {
            k++;
            modes[++top] = code;
            braces[top] = 0;
          }
        } else if (s.startsWith("__", k)) {
          throw preprocessing(k);
        } else if (c == '\'') {
          modes[++top] = string;
        } else if (c == '|') {
          modes[++top] = text;
        } else if (c == '{') {
          braces[top]++;
        } else if (c == '}' && top > 0) {
          if (braces[top] == 0) {
            top--;
          } else {
            braces[top]--;
          }
        }
      }
    }

    /** Thymeleaf's preprocessing shape: {@code __} + an expression opener + a later {@code __}. */
    private boolean preprocessesAt(int k) {
      int open = k + 2;
      return open + 1 < s.length()
          && "$*#@~".indexOf(s.charAt(open)) >= 0
          && s.charAt(open + 1) == '{'
          && s.indexOf("__", open + 2) >= 0;
    }

    private ParseError preprocessing(int at) {
      return error(at, "preprocessing (__...__) is not supported: expressions are never built from strings");
    }

    // ---- expressions ----

    Expr expr() {
      return nested(() -> {
        Expr cond = elvis();
        ws();
        if (at("?") && !at("?:")) {
          i++;
          Expr then = expr();
          Expr otherwise = null;
          ws();
          if (at(":") && !at("::")) {
            i++;
            otherwise = expr();
          }
          return new Expr.Conditional(cond, then, otherwise, cond.pos());
        }
        return cond;
      });
    }

    Expr elvis() {
      Expr value = or();
      if (eat("?:")) {
        return new Expr.Elvis(value, nested(this::elvis), value.pos());
      }
      return value;
    }

    Expr or() {
      Expr left = and();
      while (eat("||") || eatWord("or")) {
        left = new Expr.Binary(Expr.BinaryOp.OR, left, and(), left.pos());
      }
      return left;
    }

    Expr and() {
      Expr left = equality();
      while (eat("&&") || eatWord("and")) {
        left = new Expr.Binary(Expr.BinaryOp.AND, left, equality(), left.pos());
      }
      return left;
    }

    Expr equality() {
      Expr left = relational();
      while (true) {
        Expr.BinaryOp op;
        if (eat("==") || eatWord("eq")) {
          op = Expr.BinaryOp.EQ;
        } else if (eat("!=") || eatWord("ne")) {
          op = Expr.BinaryOp.NE;
        } else if (at("=")) {
          throw error(i, "assignment (=) is not supported; use == to compare");
        } else {
          return left;
        }
        left = new Expr.Binary(op, left, relational(), left.pos());
      }
    }

    Expr relational() {
      Expr left = additive();
      Expr.BinaryOp op;
      if (eat("<=") || eatWord("le")) {
        op = Expr.BinaryOp.LE;
      } else if (eat(">=") || eatWord("ge")) {
        op = Expr.BinaryOp.GE;
      } else if (eat("<") || eatWord("lt")) {
        op = Expr.BinaryOp.LT;
      } else if (eat(">") || eatWord("gt")) {
        op = Expr.BinaryOp.GT;
      } else {
        return left;
      }
      return new Expr.Binary(op, left, additive(), left.pos());
    }

    Expr additive() {
      Expr left = mult();
      while (true) {
        ws();
        rejectLambda();
        Expr.BinaryOp op;
        if (at("+")) {
          op = Expr.BinaryOp.ADD;
        } else if (at("-")) {
          op = Expr.BinaryOp.SUB;
        } else {
          return left;
        }
        i++;
        left = new Expr.Binary(op, left, mult(), left.pos());
      }
    }

    Expr mult() {
      Expr left = unary();
      while (true) {
        ws();
        Expr.BinaryOp op;
        if (at("*") && !at("*{")) {
          op = Expr.BinaryOp.MUL;
        } else if (at("/")) {
          op = Expr.BinaryOp.DIV;
        } else if (at("%")) {
          op = Expr.BinaryOp.MOD;
        } else {
          return left;
        }
        i++;
        left = new Expr.Binary(op, left, unary(), left.pos());
      }
    }

    void rejectLambda() {
      if (at("->")) {
        throw error(i, "lambdas (->) are not supported: compute the value in Java and pass it as a parameter");
      }
    }

    Expr unary() {
      ws();
      int at = i;
      Expr.UnaryOp op;
      if (at("!") && !at("!=")) {
        i++;
        op = Expr.UnaryOp.NOT;
      } else if (eatWord("not")) {
        op = Expr.UnaryOp.NOT;
      } else if (at("-")) {
        rejectLambda();
        i++;
        op = Expr.UnaryOp.NEGATE;
      } else {
        return postfix(primary());
      }
      return new Expr.Unary(op, nested(this::unary), pos(at));
    }

    Expr postfix(Expr e) {
      while (true) {
        ws();
        if (at(".![")) {
          throw error(i, "collection projection (.![...]) is not supported: compute the list in Java and pass it as a"
              + " parameter");
        }
        if (at(".?[") || at(".^[") || at(".$[")) {
          throw error(i, "collection selection (.?[...]) is not supported: filter the list in Java and pass it as a"
              + " parameter");
        }
        boolean nullSafe;
        if (at("?.")) {
          nullSafe = true;
          i += 2;
        } else if (at(".")) {
          nullSafe = false;
          i++;
        } else if (at("[")) {
          i++;
          Expr index = expr();
          expect("]", "']' to close the index");
          e = new Expr.Index(e, index, e.pos());
          continue;
        } else {
          return e;
        }
        String name = ident("a property or method name after '" + (nullSafe ? "?." : ".") + "'");
        ws();
        e = at("(")
            ? new Expr.MethodCall(e, name, args(), nullSafe, e.pos())
            : new Expr.Property(e, name, nullSafe, e.pos());
      }
    }

    List<Expr> args() {
      expect("(", "'('");
      List<Expr> args = new ArrayList<>();
      if (eat(")")) {
        return args;
      }
      do {
        args.add(expr());
      } while (eat(","));
      expect(")", "',' or ')' in the argument list");
      return args;
    }

    Expr primary() {
      ws();
      int at = i;
      if (atEnd()) {
        throw error(i, "expected an expression but reached the end");
      }
      char c = peek();
      if (c == '\'') {
        return string();
      }
      if (c >= '0' && c <= '9') {
        return number();
      }
      if (c == '(') {
        i++;
        Expr e = expr();
        expect(")", "')'");
        return e;
      }
      if (at("${")) {
        i += 2;
        Expr e = expr();
        expect("}", "'}' to close '${'");
        return e;
      }
      if (at("*{")) {
        i += 2;
        ws();
        int nameAt = i;
        String name = ident("a property name after '*{'");
        Expr e = postfix(new Expr.SelectionVar(name, pos(nameAt)));
        expect("}", "'}' to close '*{'");
        return e;
      }
      if (at("#{")) {
        return message();
      }
      if (c == '#') {
        i++;
        String ns = ident("a namespace after '#'");
        if (!at(".")) {
          throw error(i, "expected '.' after '#" + ns + "' but " + found());
        }
        i++;
        String fn = ident("a function name after '#" + ns + ".'");
        ws();
        if (!at("(")) {
          throw error(i, "expected '(' after '#" + ns + "." + fn + "' but " + found());
        }
        return new Expr.FunctionCall(ns, fn, args(), pos(at));
      }
      if (at("@{")) {
        return link();
      }
      if (c == '@') {
        throw error(at, "bean references (@name) are not supported; pass values in as template parameters");
      }
      if (c == '|') {
        return interpolation();
      }
      if (at("~{")) {
        return new Expr.FragmentExpr(fragment(), pos(at));
      }
      if (identStart(c)) {
        String name = identRaw();
        switch (name) {
          case "true":
            return new Expr.Literal(Boolean.TRUE, pos(at));
          case "false":
            return new Expr.Literal(Boolean.FALSE, pos(at));
          case "null":
            return new Expr.Literal(null, pos(at));
          case "new":
            throw error(at, "object creation (new) is not supported: pass the value in as a template parameter");
          case "and":
          case "or":
          case "eq":
          case "ne":
          case "lt":
          case "gt":
          case "le":
          case "ge":
            throw error(at, "expected an expression but found operator '" + name + "'");
          default:
            break;
        }
        ws();
        if (at("(")) {
          if (name.equals("T")) {
            throw error(at, "type references (T(...)) are not supported: call a @TemplateFunction (e.g."
              + " numbers.sequence(1, n)) or pass the value as a parameter");
          }
          throw error(at, "'" + name + "(...)' is not a known call; call functions as 'ns." + name
              + "(...)' or '#ns." + name + "(...)'");
        }
        return new Expr.Var(name, pos(at));
      }
      throw error(at, "expected an expression but found '" + c + "'");
    }

    Expr string() {
      int at = i++;
      StringBuilder b = new StringBuilder();
      while (true) {
        if (atEnd()) {
          throw error(at, "unterminated string literal: expected a closing ' (write \\' for a quote inside the string)");
        }
        char c = s.charAt(i);
        if (c == '\\' && (peekAt(1) == '\'' || peekAt(1) == '\\')) {
          b.append(peekAt(1));
          i += 2;
        } else if (c == '\'') {
          i++;
          return new Expr.Literal(b.toString(), pos(at));
        } else {
          b.append(c);
          i++;
        }
      }
    }

    Expr number() {
      int at = i;
      while (peek() >= '0' && peek() <= '9') {
        i++;
      }
      Object value;
      try {
        if (peek() == '.' && peekAt(1) >= '0' && peekAt(1) <= '9') {
          i++;
          while (peek() >= '0' && peek() <= '9') {
            i++;
          }
          value = Double.parseDouble(s.substring(at, i));
        } else if (peek() == 'L' || peek() == 'l') {
          value = Long.parseLong(s.substring(at, i));
          i++;
        } else {
          value = Integer.parseInt(s.substring(at, i));
        }
      } catch (NumberFormatException e) {
        throw error(at, "number literal out of range: " + s.substring(at, i)
            + " (int, or long with an L suffix)");
      }
      if (identPart(peek())) {
        throw error(i, "invalid number literal: expected a digit, '.' or 'L' but found '" + peek() + "'");
      }
      return new Expr.Literal(value, pos(at));
    }

    static boolean isDynamic(String literal) {
      return literal.indexOf('$') >= 0
          || literal.indexOf('\'') >= 0
          || literal.contains("*{")
          || literal.contains("#{")
          || literal.contains("@{")
          || literal.contains("~{");
    }

    Expr message() {
      int at = i;
      i += 2;
      ws();
      int keyAt = i;
      while (!atEnd() && peek() != '(' && peek() != '}') {
        i++;
      }
      String key = JavaStrings.stripJava(s.substring(keyAt, i));
      if (key.isEmpty()) {
        throw error(keyAt, "expected a message key after '#{' but " + found());
      }
      if (isDynamic(key) || key.indexOf('{') >= 0) {
        throw error(keyAt, "dynamic message keys are not supported; the key must be a literal such as #{owner.name}");
      }
      for (int k = 0; k < key.length(); k++) {
        char c = key.charAt(k);
        if (!identPart(c) && c != '.' && c != '-') {
          throw error(keyAt + k, "invalid character '" + c + "' in message key '" + key
              + "': keys use letters, digits, '.', '-' and '_'");
        }
      }
      ws();
      List<Expr> args = at("(") ? args() : Backports.listOf();
      expect("}", "'}' to close '#{'");
      return new Expr.Message(key, args, pos(at));
    }

    Expr link() {
      int at = i;
      i += 2;
      ws();
      int pathAt = i;
      int braces = 0;
      while (!atEnd()) {
        char c = peek();
        if (c == '{') {
          braces++;
        } else if (c == '}') {
          if (braces == 0) {
            break;
          }
          braces--;
        } else if (c == '(' && braces == 0) {
          break;
        }
        i++;
      }
      String path = JavaStrings.stripJava(s.substring(pathAt, i));
      // @{'/owners?page=1'}: one quoted literal without escapes is a constant (#806).
      if (path.length() > 2 && path.charAt(0) == '\'' && path.indexOf('\'', 1) == path.length() - 1 && path.indexOf('\\') < 0) {
        path = path.substring(1, path.length() - 1);
        pathAt++;
      }
      if (path.isEmpty()) {
        throw error(pathAt, "expected a link path after '@{' but " + found());
      }
      if (isDynamic(path)) {
        throw error(pathAt, "dynamic link paths are not supported; the path must be a literal such as @{/owners/{id}(id=owner.id)}");
      }
      checkPath(path, pathAt);
      List<Assignment> params = new ArrayList<>();
      ws();
      if (at("(")) {
        i++;
        do {
          ws();
          int nameAt = i;
          String name = ident("a link parameter name");
          ws();
          if (at(",") || at(")")) {
            // Thymeleaf's valueless parameter: @{/cart(add,id=x)} renders /cart?add&id=...
            params.add(new Assignment(name, null, pos(nameAt)));
            continue;
          }
          if (!eat("=") || at("=")) {
            throw error(i, "expected '=', ',' or ')' after link parameter '" + name + "' but " + found());
          }
          params.add(new Assignment(name, expr(), pos(nameAt)));
        } while (eat(","));
        expect(")", "',' or ')' in the link parameters");
      }
      expect("}", "'}' to close '@{'");
      return new Expr.Link(path, params, pos(at));
    }

    void checkPath(String path, int pathAt) {
      int open = -1;
      for (int k = 0; k < path.length(); k++) {
        char c = path.charAt(k);
        if (JavaStrings.isJavaWhitespace(c)) {
          throw error(pathAt + k, "link path must not contain whitespace: percent-encode it (%20) or pass the value as a"
              + " parameter, e.g. @{/search(q=term)}");
        } else if (c == '{') {
          if (open >= 0) {
            throw error(pathAt + k, "nested '{' in link path: write a path variable such as {id}");
          }
          open = k;
        } else if (c == '}') {
          if (open < 0) {
            throw error(pathAt + k, "unmatched '}' in link path: write a path variable such as {id}");
          }
          String var = path.substring(open + 1, k);
          if (!isIdentifier(var)) {
            throw error(pathAt + open, "invalid path variable '{" + var + "}': write a name such as {id}");
          }
          open = -1;
        }
      }
    }

    Expr interpolation() {
      int at = i++;
      List<Expr> parts = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      int textAt = i;
      while (true) {
        if (atEnd()) {
          throw error(at, "unterminated literal substitution: expected a closing '|' (e.g. |Hello, ${name}!|)");
        }
        boolean end = peek() == '|';
        boolean expr = at("${") || at("*{") || at("#{");
        if (end || expr) {
          if (text.length() > 0) {
            parts.add(new Expr.Literal(text.toString(), pos(textAt)));
            text.setLength(0);
          }
          if (end) {
            i++;
            return new Expr.Interpolation(parts, pos(at));
          }
          parts.add(primary());
          textAt = i;
        } else {
          text.append(peek());
          i++;
        }
      }
    }

    FragmentRef fragment() {
      int at = i;
      i += 2;
      FragmentRef ref = fragmentBody(at);
      expect("}", "'}' to close '~{'");
      return ref;
    }

    /**
     * The name after {@code ::}: a fragment name or, in a same-template selection, a tag name. A tag
     * name may contain '-' (a custom element such as {@code my-card}), and {@code t:block} is the one
     * with a prefix. Other markup selectors ({@code #id}, {@code .class}, paths) are not
     * supported.
     */
    String selector() {
      String name = fragmentName("a fragment or tag name after '::' (markup selectors such as #id and .class are not"
          + " supported)");
      int start = i - name.length();
      if (at(":") && !at("::")) {
        i++;
        String qualified = name + ":" + (identStart(peek()) ? identRaw() : "");
        if (!qualified.equals("t:block")) {
          throw error(start, "'" + qualified + "' after '::': the only tag name with a prefix that can be selected is"
              + " t:block" + (qualified.equals("th:block") ? " (Thwart's name for th:block)" : ""));
        }
        name = qualified;
      }
      return name;
    }

    /** A name whose parts may be joined by '-': {@code period-selector}, {@code my-card}. */
    String fragmentName(String what) {
      String name = ident(what);
      while (at("-") && identPart(peekAt(1))) {
        i++;
        name += "-" + identRaw();
      }
      return name;
    }

    /** {@code [template | this] :: selector [(args)]}, the ref positioned at {@code at}. */
    FragmentRef fragmentBody(int at) {
      ws();
      int tplAt = i;
      while (!atEnd() && !at("::") && peek() != '}') {
        i++;
      }
      String tpl = JavaStrings.stripJava(s.substring(tplAt, i));
      if (isDynamic(tpl) || tpl.indexOf('{') >= 0) {
        throw error(tplAt, "dynamic template names are not supported; the template must be a literal such as ~{fragments/layout :: main}");
      }
      if (!at("::")) {
        throw error(i, "expected '::' in fragment reference (e.g. ~{template :: name}) but " + found());
      }
      for (int k = 0; k < tpl.length(); k++) {
        char c = tpl.charAt(k);
        if (!identPart(c) && c != '/' && c != '-' && c != '.') {
          throw error(tplAt + k, "invalid character '" + c + "' in template name '" + tpl
              + "': names use letters, digits, '/', '-', '_' and '.'");
        }
      }
      i += 2;
      String selector = selector();
      List<FragmentRef.Arg> args = new ArrayList<>();
      ws();
      if (at("(")) {
        i++;
        if (!eat(")")) {
          Boolean named = null;
          do {
            ws();
            int argAt = i;
            String name = null;
            if (identStart(peek())) {
              String id = identRaw();
              ws();
              if (at("=") && !at("==")) {
                i++;
                name = id;
              } else {
                i = argAt;
              }
            }
            if (named != null && named != (name != null)) {
              throw error(argAt, "fragment arguments must be all named or all positional, e.g. f(a, b) or f(x=a, y=b)");
            }
            named = name != null;
            args.add(new FragmentRef.Arg(name, expr(), pos(argAt)));
          } while (eat(","));
          expect(")", "',' or ')' in the fragment arguments");
        }
      }
      return new FragmentRef(tpl.isEmpty() || tpl.equals("this") ? null : tpl, selector, args, pos(at));
    }

    // ---- declarations ----

    String attributeName() {
      ws();
      if (!identStart(peek())) {
        throw error(i, "expected an attribute or variable name but " + found());
      }
      int b = i;
      while (identPart(peek()) || peek() == '-' || peek() == ':' || peek() == '.') {
        i++;
      }
      return s.substring(b, i);
    }

    String qualifiedName(String what) {
      StringBuilder b = new StringBuilder(ident(what));
      while (peek() == '.' && identStart(peekAt(1))) {
        i++;
        b.append('.').append(identRaw());
      }
      return b.toString();
    }

    TypeRef type() {
      ws();
      int at = i;
      String name = qualifiedName("a type name");
      List<TypeRef> typeArgs = new ArrayList<>();
      if (eat("<")) {
        do {
          ws();
          if (at("?")) {
            throw error(i, "wildcard types are not supported: write the element type, e.g. List<Owner>");
          }
          typeArgs.add(nested(this::type));
        } while (eat(","));
        expect(">", "',' or '>' in the type arguments of '" + name + "'");
      }
      int dims = 0;
      while (eat("[")) {
        expect("]", "']'");
        dims++;
      }
      return new TypeRef(name, typeArgs, dims, pos(at));
    }

    List<ParamDecl> paramList() {
      List<ParamDecl> out = new ArrayList<>();
      do {
        ws();
        int at = i;
        TypeRef type = type();
        ws();
        if (!identStart(peek())) {
          throw error(i, "expected a parameter name after type '" + type.name()
              + "' but " + found() + " (parameters are declared as 'Type name', e.g. 'OwnerView owner')");
        }
        out.add(new ParamDecl(type, identRaw(), pos(at)));
      } while (eat(","));
      return Backports.listCopy(out);
    }
  }
}
