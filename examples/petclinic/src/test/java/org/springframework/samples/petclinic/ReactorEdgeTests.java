package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The Tessera processor runs from {@code annotationProcessorPaths}, which Maven does not
 * treat as a reactor edge. Without a direct dependency on it, {@code -pl examples/petclinic
 * -am} and parallel builds can compile petclinic before (or without) the processor (#60).
 */
class ReactorEdgeTests {

	@Test
	void everyTesseraProcessorPathIsAlsoAProvidedDependency() throws Exception {
		var factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Element project = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile()).getDocumentElement();

		List<String> processorPaths = new ArrayList<>();
		var paths = project.getElementsByTagName("path");
		for (int i = 0; i < paths.getLength(); i++) {
			Element path = (Element) paths.item(i);
			if ("dev.tessera".equals(text(path, "groupId"))) {
				processorPaths.add(text(path, "artifactId"));
			}
		}
		assertThat(processorPaths).contains("tessera-compiler");

		List<String> providedDeps = new ArrayList<>();
		Element dependencies = child(project, "dependencies");
		for (Node n = dependencies.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element dep && "dev.tessera".equals(text(dep, "groupId"))
					&& "provided".equals(text(dep, "scope"))) {
				providedDeps.add(text(dep, "artifactId"));
			}
		}
		assertThat(providedDeps).containsAll(processorPaths);
	}

	private static Element child(Element parent, String name) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(name)) {
				return e;
			}
		}
		return null;
	}

	private static String text(Element parent, String name) {
		Element e = child(parent, name);
		return e == null ? null : e.getTextContent().trim();
	}

}
