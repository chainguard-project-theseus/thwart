package dev.tessera.maven;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what {@link ReviewMojo} reports from one class file (#428): its {@code @TemplateFunction}
 * methods. Only the parts of the class file format this needs are decoded.
 */
final class ClassScan {
  static final String RUNTIME = "dev/tessera/runtime/";
  private static final String FUNCTIONS = "L" + RUNTIME + "TemplateFunctions;";
  private static final String FUNCTION = "L" + RUNTIME + "TemplateFunction;";

  /** A {@code @TemplateFunction} method. */
  static final class Function {
    final String namespace;
    final String name;
    final String descriptor;
    final String position;

    Function(String namespace, String name, String descriptor, String position) {
      this.namespace = namespace;
      this.name = name;
      this.descriptor = descriptor;
      this.position = position;
    }
  }

  final List<Function> functions = new ArrayList<>();

  private final Object[] pool;
  private String className;
  private String sourceFile;

  private ClassScan(int poolSize) {
    pool = new Object[poolSize];
  }

  /** Scans one class file. */
  static ClassScan scan(byte[] bytes) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    if (in.readInt() != 0xCAFEBABE) {
      throw new IOException("not a class file");
    }
    in.readUnsignedShort(); // minor
    in.readUnsignedShort(); // major
    ClassScan s = new ClassScan(in.readUnsignedShort());
    s.readPool(in);
    s.read(in);
    return s;
  }

  private void readPool(DataInputStream in) throws IOException {
    for (int i = 1; i < pool.length; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1: // Utf8
          pool[i] = in.readUTF();
          break;
        case 3: // Integer
        case 4: // Float
          in.readInt();
          break;
        case 5: // Long
        case 6: // Double
          in.readLong();
          i++;
          break;
        case 7: // Class
        case 8: // String
        case 16: // MethodType
        case 19: // Module
        case 20: // Package
          pool[i] = new int[] {in.readUnsignedShort()};
          break;
        case 15: // MethodHandle: kind, reference
          in.readUnsignedByte();
          in.readUnsignedShort();
          break;
        case 9: // Fieldref
        case 10: // Methodref
        case 11: // InterfaceMethodref
        case 12: // NameAndType
        case 17: // Dynamic
        case 18: // InvokeDynamic
          in.readInt();
          break;
        default:
          throw new IOException("unknown constant pool tag " + tag);
      }
    }
  }

  private static final class Method {
    String name;
    String descriptor;
    boolean function;
    int line; // the first line of its code, or 0
  }

  private void read(DataInputStream in) throws IOException {
    in.readUnsignedShort(); // access
    className = utf(((int[]) pool[in.readUnsignedShort()])[0]);
    in.readUnsignedShort(); // super
    in.skipBytes(2 * in.readUnsignedShort()); // interfaces
    for (int n = in.readUnsignedShort(); n > 0; n--) { // fields
      in.skipBytes(6);
      skipAttributes(in);
    }
    List<Method> methods = new ArrayList<>();
    for (int n = in.readUnsignedShort(); n > 0; n--) {
      Method m = new Method();
      in.readUnsignedShort();
      m.name = utf(in.readUnsignedShort());
      m.descriptor = utf(in.readUnsignedShort());
      for (int a = in.readUnsignedShort(); a > 0; a--) {
        String name = utf(in.readUnsignedShort());
        int length = in.readInt();
        if (name.equals("Code")) {
          in.skipBytes(4); // max_stack, max_locals
          in.skipBytes(in.readInt()); // code
          in.skipBytes(8 * in.readUnsignedShort()); // exception table
          for (int c = in.readUnsignedShort(); c > 0; c--) {
            String cname = utf(in.readUnsignedShort());
            int clength = in.readInt();
            if (cname.equals("LineNumberTable") && m.line == 0) {
              int rest = clength - 2;
              if (in.readUnsignedShort() > 0) { // line_number_table_length may be 0
                in.skipBytes(2); // start_pc
                m.line = in.readUnsignedShort();
                rest -= 4;
              }
              in.skipBytes(rest);
            } else {
              in.skipBytes(clength);
            }
          }
        } else if (name.equals("RuntimeInvisibleAnnotations") || name.equals("RuntimeVisibleAnnotations")) {
          List<String> found = new ArrayList<>();
          annotations(in, found);
          m.function |= found.contains(FUNCTION);
        } else {
          in.skipBytes(length);
        }
      }
      methods.add(m);
    }
    String namespace = null;
    for (int a = in.readUnsignedShort(); a > 0; a--) {
      String name = utf(in.readUnsignedShort());
      int length = in.readInt();
      if (name.equals("SourceFile")) {
        sourceFile = utf(in.readUnsignedShort());
      } else if (name.equals("RuntimeInvisibleAnnotations") || name.equals("RuntimeVisibleAnnotations")) {
        List<String> found = new ArrayList<>();
        String ns = annotations(in, found);
        if (found.contains(FUNCTIONS)) {
          namespace = ns;
        }
      } else {
        in.skipBytes(length);
      }
    }
    for (Method m : methods) {
      if (m.function && namespace != null) {
        functions.add(new Function(namespace, m.name, m.descriptor, position(m)));
      }
    }
  }

  /** {@code com/example/Pages.java:12}, or the class and method when the class has no debug information. */
  private String position(Method m) {
    if (sourceFile == null || m.line == 0) {
      return className.replace('/', '.') + "." + m.name;
    }
    int slash = className.lastIndexOf('/');
    return className.substring(0, slash + 1) + sourceFile + ":" + m.line;
  }

  private String utf(int index) {
    return (String) pool[index];
  }

  /**
   * Reads a {@code Runtime*Annotations} attribute into {@code found} and returns the string
   * {@code value} of a {@code @TemplateFunctions} among them, or null.
   */
  private String annotations(DataInputStream in, List<String> found) throws IOException {
    String value = null;
    for (int n = in.readUnsignedShort(); n > 0; n--) {
      String type = utf(in.readUnsignedShort());
      found.add(type);
      for (int p = in.readUnsignedShort(); p > 0; p--) {
        String name = utf(in.readUnsignedShort());
        String v = elementValue(in);
        if (type.equals(FUNCTIONS) && name.equals("value")) {
          value = v;
        }
      }
    }
    return value;
  }

  /** Reads one element_value; returns it if it is a string constant, else null. */
  private String elementValue(DataInputStream in) throws IOException {
    int tag = in.readUnsignedByte();
    switch (tag) {
      case 's':
        return utf(in.readUnsignedShort());
      case 'B': case 'C': case 'D': case 'F': case 'I': case 'J': case 'S': case 'Z': case 'c':
        in.readUnsignedShort();
        return null;
      case 'e':
        in.skipBytes(4);
        return null;
      case '@':
        in.readUnsignedShort();
        for (int p = in.readUnsignedShort(); p > 0; p--) {
          in.readUnsignedShort();
          elementValue(in);
        }
        return null;
      case '[':
        for (int k = in.readUnsignedShort(); k > 0; k--) {
          elementValue(in);
        }
        return null;
      default:
        throw new IOException("unknown annotation element tag " + (char) tag);
    }
  }

  private static void skipAttributes(DataInputStream in) throws IOException {
    for (int a = in.readUnsignedShort(); a > 0; a--) {
      in.skipBytes(2);
      in.skipBytes(in.readInt());
    }
  }
}
