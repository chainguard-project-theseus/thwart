package dev.tessera.compiler;

import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleTypeVisitor8;

/** Encodes values and types as Java source for generated code. */
public final class JavaStrings {

  private JavaStrings() {}

  // Java text: identifiers, the expression language, MessageFormat patterns, options and Tessera's own
  // directive values. These are the only Unicode case mappings and character classes in the compiler:
  // Java names may be non-ASCII, and javac, JavaBeans and Thymeleaf use Character's classes. Markup,
  // CSS and URLs never come here; they use HtmlParser's ASCII helpers, which AsciiOnlyTest enforces.

  /** {@link Character#isWhitespace(char)}: Java whitespace, which is not HTML whitespace. */
  public static boolean isJavaWhitespace(char c) {
    return Character.isWhitespace(c);
  }

  /** {@link Character#isLetter(char)}, as an expression identifier starts. */
  public static boolean isJavaLetter(char c) {
    return Character.isLetter(c);
  }

  /** {@link Character#isLetterOrDigit(char)}, as an expression identifier continues. */
  public static boolean isJavaLetterOrDigit(char c) {
    return Character.isLetterOrDigit(c);
  }

  /** {@code String.strip()} (Java 11): {@code s} without leading and trailing Java whitespace. */
  public static String stripJava(String s) {
    return stripJavaTrailing(stripJavaLeading(s));
  }

  /** {@code String.stripLeading()} (Java 11). */
  public static String stripJavaLeading(String s) {
    int i = 0;
    while (i < s.length() && Character.isWhitespace(s.codePointAt(i))) {
      i += Character.charCount(s.codePointAt(i));
    }
    return s.substring(i);
  }

  /** {@code String.stripTrailing()} (Java 11). */
  public static String stripJavaTrailing(String s) {
    int i = s.length();
    while (i > 0 && Character.isWhitespace(s.codePointBefore(i))) {
      i -= Character.charCount(s.codePointBefore(i));
    }
    return s.substring(0, i);
  }

  /** {@code String.isBlank()} (Java 11): only Java whitespace. */
  public static boolean isJavaBlank(String s) {
    return stripJavaLeading(s).isEmpty();
  }

  /** {@code String.trim()}: {@code s} without leading and trailing chars up to U+0020. */
  public static String trimJava(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) <= ' ') {
      start++;
    }
    while (end > start && s.charAt(end - 1) <= ' ') {
      end--;
    }
    return s.substring(start, end);
  }

  /** {@code name} with its first char upper-cased, as a getter name is built from a property. */
  public static String capitalize(String name) {
    return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  /** JavaBeans decapitalization: {@code Name} to {@code name}, {@code URL} stays {@code URL}. */
  public static String decapitalize(String name) {
    if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  /** {@code name} with its leading run of upper-case chars lower-cased: {@code URLName} to {@code urlname}. */
  public static String lowerLeadingUpper(String name) {
    StringBuilder sb = new StringBuilder(name);
    for (int i = 0; i < sb.length() && Character.isUpperCase(sb.charAt(i)); i++) {
      sb.setCharAt(i, Character.toLowerCase(sb.charAt(i)));
    }
    return sb.toString();
  }

  private static final String FRAGMENT = "dev.tessera.runtime.Fragment";

  /**
   * The Java type of a {@code Fragment} in generated code (#676): {@code Fragment} has no
   * instances, so a fragment argument is a lambda of this type, passed only to private methods.
   */
  public static final String FRAGMENT_BODY = FRAGMENT + ".Body";

  /**
   * {@code t} as Java source, without type-use annotations (#627). {@code TypeMirror.toString()}
   * keeps them, and JDK 8's javac writes {@code List<@NotNull Integer>}'s element as
   * {@code (@NotNull :: java.lang.Integer)}, which does not compile. Generics, arrays, wildcards and
   * inner classes of generic classes are kept. {@code Fragment} is written {@link #FRAGMENT_BODY}.
   */
  public static String typeName(TypeMirror t) {
    return t.accept(TYPE_NAME, null);
  }

  private static final TypeVisitor<String, Void> TYPE_NAME =
      new SimpleTypeVisitor8<String, Void>() {
        @Override
        protected String defaultAction(TypeMirror t, Void unused) {
          return t.toString();
        }

        @Override
        public String visitPrimitive(PrimitiveType t, Void unused) {
          switch (t.getKind()) {
            case BOOLEAN:
              return "boolean";
            case BYTE:
              return "byte";
            case SHORT:
              return "short";
            case INT:
              return "int";
            case LONG:
              return "long";
            case CHAR:
              return "char";
            case FLOAT:
              return "float";
            case DOUBLE:
              return "double";
            default:
              return t.toString();
          }
        }

        @Override
        public String visitArray(ArrayType t, Void unused) {
          return visit(t.getComponentType()) + "[]";
        }

        @Override
        public String visitDeclared(DeclaredType t, Void unused) {
          TypeElement te = (TypeElement) t.asElement();
          if (te.getQualifiedName().contentEquals(FRAGMENT)) {
            return FRAGMENT_BODY;
          }
          TypeMirror outer = t.getEnclosingType();
          StringBuilder sb = new StringBuilder();
          if (outer.getKind() == TypeKind.DECLARED) {
            // An inner (non-static) class: Outer<A>.Inner, as javac writes it.
            sb.append(visit(outer)).append('.').append(te.getSimpleName());
          } else {
            sb.append(te.getQualifiedName());
          }
          List<? extends TypeMirror> args = t.getTypeArguments();
          if (!args.isEmpty()) {
            sb.append('<');
            for (int i = 0; i < args.size(); i++) {
              sb.append(i == 0 ? "" : ",").append(visit(args.get(i)));
            }
            sb.append('>');
          }
          return sb.toString();
        }

        @Override
        public String visitTypeVariable(TypeVariable t, Void unused) {
          return t.asElement().getSimpleName().toString();
        }

        @Override
        public String visitWildcard(WildcardType t, Void unused) {
          if (t.getExtendsBound() != null) {
            return "? extends " + visit(t.getExtendsBound());
          }
          return t.getSuperBound() != null ? "? super " + visit(t.getSuperBound()) : "?";
        }

        @Override
        public String visitIntersection(IntersectionType t, Void unused) {
          StringBuilder sb = new StringBuilder();
          for (TypeMirror b : t.getBounds()) {
            sb.append(sb.length() == 0 ? "" : " & ").append(visit(b));
          }
          return sb.toString();
        }
      };

  /**
   * A Java string literal for {@code s}. Line terminators, quotes and backslashes use escape
   * sequences (never {@code \\u} escapes, which javac would decode before lexing); other C0
   * control characters use octal escapes, and DEL and non-ASCII characters use {@code \\uXXXX}.
   */
  public static String literal(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) {
            b.append(String.format("\\%03o", (int) c));
          } else if (c >= 0x7f) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    return b.append('"').toString();
  }
}
