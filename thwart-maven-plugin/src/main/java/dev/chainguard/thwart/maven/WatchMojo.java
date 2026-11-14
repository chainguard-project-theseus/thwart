package dev.chainguard.thwart.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Recompiles the module whenever a template or message file changes, for hot reload with Spring
 * Boot DevTools (#426): run {@code mvn thwart:watch} in a second terminal next to {@code mvn
 * spring-boot:run}. DevTools restarts the application when the new classes land.
 *
 * <p>Every half second it compares the files the {@code check} goal fingerprints (the templates
 * directory and the {@code messages} basenames) with the last time it looked. After a change it runs
 * {@code mvn -q -f <this pom> compile} with the Maven that runs this goal and its {@code -o},
 * {@code -P} and {@code -D} options (not {@code -s}; {@code .mvn/maven.config} applies anyway), so
 * the processor, its checks and its {@code file:line:col} errors are the build's own; the project's
 * {@code check} goal makes that compile rerun the processor. A failed compile is reported and the
 * watch goes on. It is a development tool: never bind it to a phase.
 */
@Mojo(name = "watch", threadSafe = true)
public class WatchMojo extends AbstractMojo {
  static final long INTERVAL_MILLIS = 500;

  /** The templates root, as for {@code check}. */
  @Parameter(defaultValue = "${project.basedir}/src/main/resources/templates", required = true)
  File templates;

  /** The message bundle basenames, as for {@code check}. */
  @Parameter(property = "thwart.messages")
  String messages;

  @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
  File basedir;

  @Parameter(defaultValue = "${project.file}", readonly = true, required = true)
  File pom;

  @Parameter(defaultValue = "${settings.offline}", readonly = true)
  boolean offline;

  /** The command line's {@code -P} profiles, passed on to the compile. */
  @Parameter(defaultValue = "${session.request.activeProfiles}", readonly = true)
  List<String> activeProfiles = new ArrayList<>();

  /** The command line's {@code -P !id} profiles, passed on to the compile. */
  @Parameter(defaultValue = "${session.request.inactiveProfiles}", readonly = true)
  List<String> inactiveProfiles = new ArrayList<>();

  /** The command line's {@code -D} properties, passed on to the compile. */
  @Parameter(defaultValue = "${session.userProperties}", readonly = true)
  Properties userProperties = new Properties();

  private byte[] last;

  @Override
  public void execute() throws MojoExecutionException {
    if (!templates.isDirectory()) {
      throw new MojoExecutionException(
          "Thwart: no templates directory " + templates + "; set the plugin's templates parameter");
    }
    getLog().info("Thwart: watching " + templates + (messages == null ? "" : " and " + messages)
        + "; recompiling on every change (Ctrl-C to stop)");
    try {
      last = fingerprint();
      while (true) {
        Thread.sleep(INTERVAL_MILLIS);
        step();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      throw new MojoExecutionException("Thwart: cannot read the templates or messages (" + e + ")", e);
    }
  }

  /** Compiles once if the templates or messages changed since the last look; true if it did. */
  boolean step() throws IOException, InterruptedException {
    byte[] now = fingerprint();
    if (Arrays.equals(now, last)) {
      return false;
    }
    last = now;
    getLog().info("Thwart: templates or messages changed; compiling");
    long start = System.nanoTime();
    if (compile() == 0) {
      getLog().info(String.format("Thwart: compiled in %.1f s", (System.nanoTime() - start) / 1e9));
    } else {
      getLog().warn("Thwart: the compile failed (errors above); fix the file and save it again");
    }
    return true;
  }

  byte[] fingerprint() throws IOException {
    return TemplateFingerprint.of(templates.toPath(), CheckMojo.messagePaths(basedir, messages));
  }

  /** Runs {@link #command} and returns its exit code. */
  int compile() throws IOException, InterruptedException {
    return new ProcessBuilder(command()).directory(basedir).inheritIO().start().waitFor();
  }

  List<String> command() {
    String mvn = File.separatorChar == '\\' ? "mvn.cmd" : "mvn";
    String home = System.getProperty("maven.home");
    List<String> cmd = new ArrayList<>();
    cmd.add(home == null ? mvn : new File(new File(home, "bin"), mvn).getPath());
    cmd.addAll(Arrays.asList("-q", "-f", pom.getPath()));
    if (offline) {
      cmd.add("-o");
    }
    for (String id : activeProfiles) {
      cmd.add("-P" + id);
    }
    for (String id : inactiveProfiles) {
      cmd.add("-P!" + id);
    }
    for (String key : new TreeSet<>(userProperties.stringPropertyNames())) {
      cmd.add("-D" + key + "=" + userProperties.getProperty(key));
    }
    cmd.add("compile");
    return cmd;
  }
}
