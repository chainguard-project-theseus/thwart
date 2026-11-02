package dev.tessera.maven;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads what {@link ReviewMojo} reports from one class file (#428): its {@code @TemplateFunction}
 * methods and its unverified doors (#602): every reference to {@code SafeHtml.unchecked},
 * {@code TrustedUrl.unchecked}, {@code Fragment.unverifiedForGeneratedCode} or a generated
 * {@code internalUnverified$<name>} method, and a {@code verifiedFlowPartial()} it declares. Works
 * on bytecode, so a call is found however the source spells it (qualified, static import, method
 * reference). Only the parts of the class file format this needs are decoded.
 */
final class ClassScan {
  static final String RUNTIME = "dev/tessera/runtime/";
  private static final String FUNCTIONS = "L" + RUNTIME + "TemplateFunctions;";
  private static final String FUNCTION = "L" + RUNTIME + "TemplateFunction;";
  private static final String[] UNCHECKED_OWNERS = {RUNTIME + "SafeHtml", RUNTIME + "TrustedUrl"};
  /** A method a template class declares; declared anywhere else it vouches for output nothing checked. */
  static final String VERIFIED_FLOW_PARTIAL = "verifiedFlowPartial";

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

  /**
   * An unverified door at {@code position}: a reference to {@code target}, e.g.
   * {@code SafeHtml.unchecked}, or a {@code verifiedFlowPartial()} declaration.
   */
  static final class Door {
    final String target;
    final String position;

    Door(String target, String position) {
      this.target = target;
      this.position = position;
    }
  }

  final List<Function> functions = new ArrayList<>();
  /** The doors of this class; {@link ReviewMojo} drops them if the class is Tessera-generated. */
  final List<Door> doors = new ArrayList<>();

  private final Object[] pool;
  private final int[] tags;
  private String className;
  private String sourceFile;

  private ClassScan(int poolSize) {
    pool = new Object[poolSize];
    tags = new int[poolSize];
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
      tags[i] = tag;
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
          pool[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()};
          break;
        case 9: // Fieldref
        case 10: // Methodref
        case 11: // InterfaceMethodref
        case 12: // NameAndType
        case 17: // Dynamic
        case 18: // InvokeDynamic
          pool[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
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
    byte[] code;
    int[] lines = new int[0]; // start_pc, line pairs
  }

  private void read(DataInputStream in) throws IOException {
    in.readUnsignedShort(); // access
    className = className(in.readUnsignedShort());
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
          m.code = new byte[in.readInt()];
          in.readFully(m.code);
          in.skipBytes(8 * in.readUnsignedShort()); // exception table
          for (int c = in.readUnsignedShort(); c > 0; c--) {
            String cname = utf(in.readUnsignedShort());
            int clength = in.readInt();
            if (cname.equals("LineNumberTable")) {
              int count = in.readUnsignedShort();
              int[] lines = new int[m.lines.length + 2 * count];
              System.arraycopy(m.lines, 0, lines, 0, m.lines.length);
              for (int k = m.lines.length; k < lines.length; k++) {
                lines[k] = in.readUnsignedShort();
              }
              m.lines = lines;
            } else {
              in.skipBytes(clength);
            }
          }
        } else if (name.equals("RuntimeInvisibleAnnotations") || name.equals("RuntimeVisibleAnnotations")) {
          m.function |= annotations(in).contains(FUNCTION);
        } else {
          in.skipBytes(length);
        }
      }
      methods.add(m);
    }
    String namespace = null;
    List<int[]> bootstrap = new ArrayList<>();
    for (int a = in.readUnsignedShort(); a > 0; a--) {
      String name = utf(in.readUnsignedShort());
      int length = in.readInt();
      if (name.equals("SourceFile")) {
        sourceFile = utf(in.readUnsignedShort());
      } else if (name.equals("BootstrapMethods")) {
        for (int b = in.readUnsignedShort(); b > 0; b--) {
          in.readUnsignedShort(); // bootstrap method
          int[] args = new int[in.readUnsignedShort()];
          for (int k = 0; k < args.length; k++) {
            args[k] = in.readUnsignedShort();
          }
          bootstrap.add(args);
        }
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
        functions.add(new Function(namespace, m.name, m.descriptor, position(m, m.lines.length > 1 ? m.lines[1] : 0)));
      }
      if (m.code != null) {
        if (m.name.equals(VERIFIED_FLOW_PARTIAL) && m.descriptor.equals("()Z")) {
          doors.add(new Door(VERIFIED_FLOW_PARTIAL + "() override", position(m, m.lines.length > 1 ? m.lines[1] : 0)));
        }
        scanCode(m, bootstrap);
      }
    }
  }

  /** The internal name of the class, e.g. {@code com/example/Pages}. */
  String className() {
    return className;
  }

  /** The internal names of the classes whose methods this class references. */
  Set<String> methodOwners() {
    Set<String> owners = new HashSet<>();
    for (int i = 1; i < pool.length; i++) {
      if (tags[i] == 10 || tags[i] == 11) {
        owners.add(className(((int[]) pool[i])[0]));
      }
    }
    return owners;
  }

  /** Walks the instructions of {@code m}, recording each reference to a door. */
  private void scanCode(Method m, List<int[]> bootstrap) throws IOException {
    byte[] code = m.code;
    int pc = 0;
    while (pc < code.length) {
      int op = code[pc] & 0xff;
      int target = 0;
      if (op >= 0xb6 && op <= 0xb9) { // invokevirtual, invokespecial, invokestatic, invokeinterface
        target = methodRef(u2(code, pc + 1));
      } else if (op == 0x12 || op == 0x13) { // ldc, ldc_w of a MethodHandle
        target = handle(op == 0x12 ? code[pc + 1] & 0xff : u2(code, pc + 1));
      } else if (op == 0xba) { // invokedynamic: a method reference such as SafeHtml::unchecked
        int bsm = ((int[]) pool[u2(code, pc + 1)])[0];
        for (int arg : bootstrap.get(bsm)) {
          if (target == 0) {
            target = handle(arg);
          }
        }
      }
      if (target != 0) {
        doors.add(new Door(memberName(target), position(m, lineAt(m, pc))));
      }
      pc += length(code, pc);
    }
  }

  /** The source line of instruction {@code pc}, or 0 if the class has no line numbers. */
  private static int lineAt(Method m, int pc) {
    int bestPc = -1;
    int line = 0;
    for (int k = 0; k + 1 < m.lines.length; k += 2) {
      if (m.lines[k] <= pc && m.lines[k] > bestPc) {
        bestPc = m.lines[k];
        line = m.lines[k + 1];
      }
    }
    return line;
  }

  /** The Methodref at {@code index} if it names a door, else 0. */
  private int methodRef(int index) {
    if (tags[index] != 10 && tags[index] != 11) {
      return 0;
    }
    int[] ref = (int[]) pool[index];
    String owner = className(ref[0]);
    String name = utf(((int[]) pool[ref[1]])[0]);
    if (name.startsWith("internalUnverified$")
        || (name.equals("unverifiedForGeneratedCode") && owner.equals(RUNTIME + "Fragment"))) {
      return index;
    }
    if (name.equals("unchecked")) {
      for (String o : UNCHECKED_OWNERS) {
        if (o.equals(owner)) {
          return index;
        }
      }
    }
    return 0;
  }

  /** The Methodref behind the MethodHandle at {@code index} if it names a door, else 0. */
  private int handle(int index) {
    return index < tags.length && tags[index] == 15 ? methodRef(((int[]) pool[index])[1]) : 0;
  }

  /** {@code SafeHtml.unchecked} for a Methodref. */
  private String memberName(int methodRef) {
    int[] ref = (int[]) pool[methodRef];
    String owner = className(ref[0]);
    return owner.substring(owner.lastIndexOf('/') + 1) + "." + utf(((int[]) pool[ref[1]])[0]);
  }

  /** {@code com/example/Pages.java:12}, or the class and method when the class has no debug information. */
  private String position(Method m, int line) {
    if (sourceFile == null || line == 0) {
      return className.replace('/', '.') + "." + m.name;
    }
    int slash = className.lastIndexOf('/');
    return className.substring(0, slash + 1) + sourceFile + ":" + line;
  }

  private String utf(int index) {
    return (String) pool[index];
  }

  private String className(int index) {
    return utf(((int[]) pool[index])[0]);
  }

  private static int u2(byte[] code, int at) {
    return ((code[at] & 0xff) << 8) | (code[at + 1] & 0xff);
  }

  private static int s4(byte[] code, int at) {
    return (u2(code, at) << 16) | u2(code, at + 2);
  }

  /** The length of the instruction at {@code pc} (JVMS 6.5). */
  private static int length(byte[] code, int pc) throws IOException {
    int op = code[pc] & 0xff;
    if (op == 0xaa || op == 0xab) { // tableswitch, lookupswitch: padded to a multiple of 4
      int at = (pc + 4) & ~3;
      if (op == 0xaa) {
        return at - pc + 12 + 4 * (s4(code, at + 8) - s4(code, at + 4) + 1);
      }
      return at - pc + 8 + 8 * s4(code, at + 4);
    }
    if (op == 0xc4) { // wide
      return (code[pc + 1] & 0xff) == 0x84 ? 6 : 4;
    }
    if (op == 0x10 || op == 0x12 || (op >= 0x15 && op <= 0x19) || (op >= 0x36 && op <= 0x3a) || op == 0xa9 || op == 0xbc) {
      return 2; // bipush, ldc, loads and stores with an index, ret, newarray
    }
    if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || (op >= 0x99 && op <= 0xa8) || (op >= 0xb2 && op <= 0xb8)
        || op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1 || op == 0xc6 || op == 0xc7) {
      return 3; // sipush, ldc_w, ldc2_w, iinc, branches, field and method instructions, new, anewarray, checkcast, instanceof, ifnull, ifnonnull
    }
    if (op == 0xc5) {
      return 4; // multianewarray
    }
    if (op == 0xb9 || op == 0xba || op == 0xc8 || op == 0xc9) {
      return 5; // invokeinterface, invokedynamic, goto_w, jsr_w
    }
    if (op > 0xc9) {
      throw new IOException("unknown opcode " + op);
    }
    return 1;
  }

  /** The annotation type descriptors of a {@code Runtime*Annotations} attribute. */
  private List<String> annotations(DataInputStream in) throws IOException {
    List<String> found = new ArrayList<>();
    annotations(in, found);
    return found;
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
