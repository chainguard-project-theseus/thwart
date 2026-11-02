package dev.tessera.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Reports the residual surface of the compiled classes (#428): the code Tessera cannot check, which
 * a security review has to read. After {@code compile}, this goal reads the main class output and
 * writes {@code target/tessera-review.txt} with:
 *
 * <ul>
 *   <li>every {@code @TemplateFunction} the module registers, marking those that return a safe type
 *       ({@code SafeHtml}, {@code TrustedUrl}, {@code Fragment}, {@code CssValue}): templates output
 *       their result without escaping, so each vouches for what it returns;
 *   <li>every unverified door outside Tessera-generated classes, at its source line (#602): a call
 *       of {@code SafeHtml.unchecked}, {@code TrustedUrl.unchecked},
 *       {@code Fragment.unverifiedForGeneratedCode} or a generated {@code internalUnverified$<name>}
 *       method, and a {@code verifiedFlowPartial()} declaration (a hand-written {@code Template}
 *       vouching for its own output).
 * </ul>
 *
 * <p>Any door fails the build. It reads bytecode, so it finds every call however the source writes
 * it (qualified, static import, method reference). A class is Tessera-generated if the module's
 * generated {@code TesseraRegistry} (named in {@code META-INF/services}) calls its methods, as it
 * calls every template class, or if it is nested in such a class. That keeps out the model classes
 * the registry only casts to. It is a review aid for code written in good faith: a module that
 * forges its own registry can hide a door from it, as Java code can write a response itself.
 */
@Mojo(name = "review", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public final class ReviewMojo extends AbstractMojo {
  static final String REPORT = "tessera-review.txt";
  private static final String SERVICES = "META-INF/services/dev.tessera.runtime.TemplateRegistry";
  private static final String[] SAFE_TYPES = {"SafeHtml", "TrustedUrl", "Fragment", "CssValue"};

  @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
  File outputDirectory;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
  File buildDirectory;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<ClassScan.Function> functions = new ArrayList<>();
    List<ClassScan.Door> doors = new ArrayList<>();
    Path report = buildDirectory.toPath().resolve(REPORT);
    Path classes = outputDirectory.toPath();
    try {
      Set<String> generated = generatedClasses(classes);
      for (Path f : classFiles(classes)) {
        ClassScan s = scan(f);
        functions.addAll(s.functions);
        if (!isGenerated(s.className(), generated)) {
          doors.addAll(s.doors);
        }
      }
      Files.createDirectories(report.getParent());
      Files.write(report, report(functions, doors).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new MojoExecutionException("Tessera: cannot write " + report + ": " + e, e);
    }
    long safe = functions.stream().filter(f -> safeType(f) != null).count();
    String summary = "Tessera: " + functions.size() + " @TemplateFunction(s), " + safe + " returning a safe type, and "
        + doors.size() + " unverified door(s); see " + report;
    if (doors.isEmpty()) {
      getLog().info(summary);
      return;
    }
    for (ClassScan.Door d : doors) {
      getLog().error(d.position + ": " + d.target + " lets output past Tessera's checks");
    }
    throw new MojoFailureException(summary + ". Use SafeHtml.sanitize or escape, or a compiled Tessera template"
        + " (SafeHtml.of, Fragment.of) instead");
  }

  private static ClassScan scan(Path f) throws MojoExecutionException {
    try {
      return ClassScan.scan(Files.readAllBytes(f));
    } catch (IOException | RuntimeException e) {
      throw new MojoExecutionException("Tessera: cannot read class file " + f + ": " + e, e);
    }
  }

  /**
   * The internal names of the classes Tessera generated in {@code classes}: the registry the
   * services file names and every class whose methods it calls (the template classes). Empty if the
   * module has no templates.
   */
  private static Set<String> generatedClasses(Path classes) throws IOException, MojoExecutionException {
    Set<String> generated = new HashSet<>();
    Path services = classes.resolve(SERVICES);
    if (!Files.isRegularFile(services)) {
      return generated;
    }
    for (String line : Files.readAllLines(services, StandardCharsets.UTF_8)) {
      String name = line.trim();
      Path registry = classes.resolve(name.replace('.', '/') + ".class");
      if (name.endsWith(".TesseraRegistry") && Files.isRegularFile(registry)) {
        ClassScan s = scan(registry);
        generated.add(s.className());
        generated.addAll(s.methodOwners());
      }
    }
    return generated;
  }

  /** Whether {@code className} is in {@code generated} or nested in (or synthetic to) a class that is. */
  static boolean isGenerated(String className, Set<String> generated) {
    for (int i = className.indexOf('$'); i >= 0; i = className.indexOf('$', i + 1)) {
      if (generated.contains(className.substring(0, i))) {
        return true;
      }
    }
    return generated.contains(className);
  }

  /** The report text: one section for functions, one for unverified doors. */
  static String report(List<ClassScan.Function> functions, List<ClassScan.Door> doors) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Tessera review surface (#428): code the template compiler trusts but cannot check.\n");
    sb.append("\n## @TemplateFunctions (").append(functions.size()).append(")\n");
    for (ClassScan.Function f : functions) {
      String safe = safeType(f);
      sb.append(f.namespace).append('.').append(f.name).append(signature(f.descriptor)).append("  ")
          .append(f.position);
      if (safe != null) {
        sb.append("  RETURNS ").append(safe).append(": output without escaping");
      }
      sb.append('\n');
    }
    sb.append("\n## Unverified doors (").append(doors.size()).append(")\n");
    for (ClassScan.Door d : doors) {
      sb.append(d.target).append("  ").append(d.position).append('\n');
    }
    return sb.toString();
  }

  /** The simple name of the Tessera safe type {@code f} returns, or null. */
  static String safeType(ClassScan.Function f) {
    String ret = f.descriptor.substring(f.descriptor.indexOf(')') + 1);
    for (String t : SAFE_TYPES) {
      if (ret.equals("L" + ClassScan.RUNTIME + t + ";")) {
        return t;
      }
    }
    return null;
  }

  /** {@code (String, int) -> SafeHtml} for {@code (Ljava/lang/String;I)Ldev/tessera/runtime/SafeHtml;}. */
  static String signature(String descriptor) {
    List<String> params = new ArrayList<>();
    int i = 1;
    while (descriptor.charAt(i) != ')') {
      int end = typeEnd(descriptor, i);
      params.add(typeName(descriptor.substring(i, end)));
      i = end;
    }
    return "(" + String.join(", ", params) + ") -> " + typeName(descriptor.substring(i + 1));
  }

  private static int typeEnd(String d, int i) {
    while (d.charAt(i) == '[') {
      i++;
    }
    return d.charAt(i) == 'L' ? d.indexOf(';', i) + 1 : i + 1;
  }

  private static String typeName(String t) {
    if (t.startsWith("[")) {
      return typeName(t.substring(1)) + "[]";
    }
    if (t.startsWith("L")) {
      String name = t.substring(1, t.length() - 1);
      return name.substring(name.lastIndexOf('/') + 1).replace('$', '.');
    }
    switch (t.charAt(0)) {
      case 'Z': return "boolean";
      case 'B': return "byte";
      case 'C': return "char";
      case 'S': return "short";
      case 'I': return "int";
      case 'J': return "long";
      case 'F': return "float";
      case 'D': return "double";
      default: return "void";
    }
  }

  /** The class files under {@code dir}, sorted, so the report is stable; none if it does not exist. */
  private static List<Path> classFiles(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return new ArrayList<>();
    }
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).sorted().collect(Collectors.toList());
    }
  }
}
