package dev.tessera.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>every call of {@code SafeHtml.unchecked} or {@code TrustedUrl.unchecked}, at its source line.
 * </ul>
 *
 * <p>With {@code failOnUnchecked} (property {@code tessera.failOnUnchecked}) an {@code unchecked}
 * call fails the build instead of only being listed. It reads bytecode, so it finds every call
 * however the source writes it (qualified, static import, method reference).
 */
@Mojo(name = "review", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public final class ReviewMojo extends AbstractMojo {
  static final String REPORT = "tessera-review.txt";
  private static final String[] SAFE_TYPES = {"SafeHtml", "TrustedUrl", "Fragment", "CssValue"};

  /** Fail the build if the classes call {@code SafeHtml.unchecked} or {@code TrustedUrl.unchecked}. */
  @Parameter(property = "tessera.failOnUnchecked", defaultValue = "false")
  boolean failOnUnchecked;

  @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
  File outputDirectory;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
  File buildDirectory;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<ClassScan.Function> functions = new ArrayList<>();
    List<ClassScan.Unchecked> unchecked = new ArrayList<>();
    Path report = buildDirectory.toPath().resolve(REPORT);
    try {
      for (Path f : classFiles(outputDirectory.toPath())) {
        ClassScan s;
        try {
          s = ClassScan.scan(Files.readAllBytes(f));
        } catch (IOException | RuntimeException e) {
          throw new MojoExecutionException("Tessera: cannot read class file " + f + ": " + e, e);
        }
        functions.addAll(s.functions);
        unchecked.addAll(s.unchecked);
      }
      Files.createDirectories(report.getParent());
      Files.write(report, report(functions, unchecked).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new MojoExecutionException("Tessera: cannot write " + report + ": " + e, e);
    }
    long safe = functions.stream().filter(f -> safeType(f) != null).count();
    String summary = "Tessera: " + functions.size() + " @TemplateFunction(s), " + safe + " returning a safe type, and "
        + unchecked.size() + " unchecked call(s); see " + report;
    if (unchecked.isEmpty() || !failOnUnchecked) {
      getLog().info(summary);
      return;
    }
    for (ClassScan.Unchecked u : unchecked) {
      getLog().error(u.position + ": " + u.target + " is not allowed (tessera.failOnUnchecked)");
    }
    throw new MojoFailureException(summary + ". tessera.failOnUnchecked is set: use SafeHtml.sanitize or escape,"
        + " a Tessera template (SafeHtml.of, Fragment.of) or a css.* value instead");
  }

  /** The report text: one section for functions, one for {@code unchecked} calls. */
  static String report(List<ClassScan.Function> functions, List<ClassScan.Unchecked> unchecked) {
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
    sb.append("\n## SafeHtml.unchecked / TrustedUrl.unchecked calls (").append(unchecked.size()).append(")\n");
    for (ClassScan.Unchecked u : unchecked) {
      sb.append(u.target).append("  ").append(u.position).append('\n');
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
