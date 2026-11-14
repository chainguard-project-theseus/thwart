package dev.chainguard.thwart.compiler.oracle;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * The spec parser the oracle checks parse renders with: the htmlparser inside validator 26.10.12
 * ({@code vnu-26} in {@code known-holes.txt}), the published jar the verifier's engine is built
 * from, unmodified.
 *
 * <p>The validator jar bundles many libraries, so it stays off the test classpath: it is loaded in
 * its own {@link URLClassLoader} whose parent is the platform loader, and called reflectively
 * (test code; principle 4 covers runtime and generated code). The loader runs with assertions
 * off, as vnu ships.
 *
 * <p>Each parse is a full document. Every XML violation policy is {@code ALLOW} (comments, text and
 * spaces as parsed), except two that a JDK DOM can't hold: a name that isn't an XML name is spelled
 * {@code U<6 hex digits>} for each bad character (the tree is unchanged, and markers, being letters
 * and digits, survive), and {@code xmlns} and {@code xmlns:*} attributes are dropped on every
 * element. A browser keeps them as plain attributes that do nothing, so the checks don't see a
 * marker in one ({@code xmlns:tqm0001}).
 */
public final class SpecParser {
  private SpecParser() {}

  private static final String BUILDER = "nu.validator.htmlparser.dom.HtmlDocumentBuilder";
  private static final String POLICY = "nu.validator.htmlparser.common.XmlViolationPolicy";

  private static final class Holder {
    static final Reflected VNU = load();
  }

  private record Reflected(Constructor<?> constructor, Object allow, Object alterInfoset, Method setNamePolicy,
      Method setXmlnsPolicy, Method setScriptingEnabled, Method setIgnoringComments, Method parse) {}

  /**
   * Parses {@code html} as a full document without altering the tree.
   *
   * @param scripting whether the parser runs with scripting on ({@code noscript} is raw text) or off
   */
  public static Document parse(String html, boolean scripting) {
    Reflected r = Holder.VNU;
    try {
      Object builder = r.constructor().newInstance(r.allow());
      r.setNamePolicy().invoke(builder, r.alterInfoset());
      r.setXmlnsPolicy().invoke(builder, r.alterInfoset());
      r.setScriptingEnabled().invoke(builder, scripting);
      r.setIgnoringComments().invoke(builder, false);
      return (Document) r.parse().invoke(builder, new InputSource(new StringReader(html)));
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("vnu-26 can't parse: " + html, e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The validator 26.10.12 jar, copied by the thwart-compiler pom (not on the classpath). */
  private static Reflected load() {
    String jar = System.getProperty("thwart.oracle.vnu");
    if (jar == null || !Files.isRegularFile(Path.of(jar))) {
      throw new IllegalStateException("thwart.oracle.vnu must name the validator jar: " + jar);
    }
    try {
      URLClassLoader loader = new URLClassLoader(new URL[] {Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
      loader.setDefaultAssertionStatus(false);
      Class<?> builder = loader.loadClass(BUILDER);
      Class<?> policy = loader.loadClass(POLICY);
      return new Reflected(builder.getConstructor(policy), policy.getField("ALLOW").get(null),
          policy.getField("ALTER_INFOSET").get(null), builder.getMethod("setNamePolicy", policy),
          builder.getMethod("setXmlnsPolicy", policy), builder.getMethod("setScriptingEnabled", boolean.class),
          builder.getMethod("setIgnoringComments", boolean.class), builder.getMethod("parse", InputSource.class));
    } catch (ReflectiveOperationException | java.net.MalformedURLException e) {
      throw new IllegalStateException("can't load vnu-26 from " + jar, e);
    }
  }
}
