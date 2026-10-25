package dev.tessera.compiler.oracle;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * The two spec parsers the HTML checks run (#318): {@code vnu-1.4}, htmlparser 1.4.16 (classic
 * {@code <select>}), and {@code vnu-26}, the htmlparser inside validator 26.10.12 (current spec).
 *
 * <p>Both jars hold the same package names, so each is loaded in its own {@link URLClassLoader}
 * whose parent is the platform loader, and called reflectively (test code; principle 4 covers
 * runtime and generated code). The loaders run with assertions off: the parsers' own consistency
 * asserts throw {@link AssertionError} on some inputs under surefire's {@code -ea}.
 *
 * <p>Each parse is a full document. Every XML violation policy is {@code ALLOW} (comments, text and
 * spaces as parsed), except two that a JDK DOM can't hold: a name that isn't an XML name is spelled
 * {@code U<6 hex digits>} for each bad character (the tree is unchanged, and markers, being letters
 * and digits, survive), and {@code xmlns} and {@code xmlns:*} attributes are dropped on every
 * element. A browser keeps them as plain attributes that do nothing, so the checks don't see a
 * marker in one ({@code xmlns:tqm0001}).
 */
public final class SpecParsers {
  private SpecParsers() {}

  /** htmlparser 1.4.16: classic {@code <select>} parsing. */
  public static final String VNU_14 = "vnu-1.4";

  /** validator 26.10.12: the current spec. */
  public static final String VNU_26 = "vnu-26";

  private static final String BUILDER = "nu.validator.htmlparser.dom.HtmlDocumentBuilder";
  private static final String POLICY = "nu.validator.htmlparser.common.XmlViolationPolicy";

  private static final class Holder {
    static final List<SpecParser> ALL = List.of(load(VNU_14, htmlparser14Jar()), load(VNU_26, validator26Jar()));
  }

  /** Both parsers, {@code vnu-1.4} first. */
  public static List<SpecParser> all() {
    return Holder.ALL;
  }

  /** The parser named {@code name}. */
  public static SpecParser named(String name) {
    for (SpecParser p : all()) {
      if (p.name().equals(name)) {
        return p;
      }
    }
    throw new IllegalArgumentException("no spec parser " + name);
  }

  /** The htmlparser 1.4.16 jar on the test classpath. */
  private static Path htmlparser14Jar() {
    try {
      return Path.of(nu.validator.htmlparser.dom.HtmlDocumentBuilder.class
          .getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The validator 26.10.12 jar, copied by the tessera-compiler pom (not on the classpath). */
  private static Path validator26Jar() {
    String jar = System.getProperty("tessera.oracle.vnu");
    if (jar == null || !Files.isRegularFile(Path.of(jar))) {
      throw new IllegalStateException("tessera.oracle.vnu must name the validator jar: " + jar);
    }
    return Path.of(jar);
  }

  private static SpecParser load(String name, Path jar) {
    try {
      URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
      loader.setDefaultAssertionStatus(false);
      return new Vnu(name, loader.loadClass(BUILDER), loader.loadClass(POLICY));
    } catch (ReflectiveOperationException | java.net.MalformedURLException e) {
      throw new IllegalStateException("can't load " + name + " from " + jar, e);
    }
  }

  private static final class Vnu implements SpecParser {
    private final String name;
    private final Constructor<?> constructor;
    private final Object allow;
    private final Object alterInfoset;
    private final Method setNamePolicy;
    private final Method setXmlnsPolicy;
    private final Method setScriptingEnabled;
    private final Method setIgnoringComments;
    private final Method parse;

    Vnu(String name, Class<?> builderType, Class<?> policyType) throws ReflectiveOperationException {
      this.name = name;
      this.constructor = builderType.getConstructor(policyType);
      this.allow = policyType.getField("ALLOW").get(null);
      this.alterInfoset = policyType.getField("ALTER_INFOSET").get(null);
      this.setNamePolicy = builderType.getMethod("setNamePolicy", policyType);
      this.setXmlnsPolicy = builderType.getMethod("setXmlnsPolicy", policyType);
      this.setScriptingEnabled = builderType.getMethod("setScriptingEnabled", boolean.class);
      this.setIgnoringComments = builderType.getMethod("setIgnoringComments", boolean.class);
      this.parse = builderType.getMethod("parse", InputSource.class);
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Document parse(String html, boolean scripting) {
      try {
        Object builder = constructor.newInstance(allow);
        setNamePolicy.invoke(builder, alterInfoset);
        setXmlnsPolicy.invoke(builder, alterInfoset);
        setScriptingEnabled.invoke(builder, scripting);
        setIgnoringComments.invoke(builder, false);
        return (Document) parse.invoke(builder, new InputSource(new StringReader(html)));
      } catch (InvocationTargetException e) {
        throw new IllegalStateException(name + " can't parse: " + html, e.getCause());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
