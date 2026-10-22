/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.apache.catalina.startup.Tomcat;
import org.apache.el.lang.ELSupport;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

final class PublishedDependenciesTest {

  @Test
  void shouldPublishTheDependencyVersionsUsedByTheLibrary() throws Exception {
    final Document document = publishedPom();
    assertPublishedVersion(document, Tomcat.class, "org.apache.tomcat.embed", "tomcat-embed-core");
    assertPublishedVersion(document, ELSupport.class, "org.apache.tomcat.embed", "tomcat-embed-el");
    assertPublishedVersion(document, WsSci.class, "org.apache.tomcat.embed", "tomcat-embed-websocket");
    assertPublishedVersion(document, JsonParser.class, "tools.jackson.core", "jackson-core");
    assertPublishedVersion(document, ObjectMapper.class, "tools.jackson.core", "jackson-databind");
  }

  @Test
  void shouldImportTheJacksonBillOfMaterials() throws Exception {
    final Document document = publishedPom();
    final String dependency = "/project/dependencyManagement/dependencies/dependency[groupId='tools.jackson' and artifactId='jackson-bom']";
    final String type = value(document, dependency + "/type");
    assertEquals("pom", type);

    final String scope = value(document, dependency + "/scope");
    assertEquals("import", scope);

    final String version = value(document, dependency + "/version");
    final Package jacksonPackage = ObjectMapper.class.getPackage();
    final String expectedVersion = jacksonPackage.getImplementationVersion();
    assertEquals(expectedVersion, version);
  }

  private static Document publishedPom() throws Exception {
    final String path = System.getProperty("mcav.published.pom");
    assertNotNull(path, "the build supplies the generated publication POM");
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of(path).toFile());
  }

  private static void assertPublishedVersion(final Document document, final Class<?> type, final String group, final String artifact)
    throws Exception {
    final String version = type.getPackage().getImplementationVersion();
    assertNotNull(version, "the resolved library declares its implementation version");
    final String dependency = "/project/dependencies/dependency[groupId='%s' and artifactId='%s']".formatted(group, artifact);
    final String publishedVersion = value(document, dependency + "/version");
    assertEquals(version, publishedVersion, "Maven consumers need the resolved version of " + artifact);

    final String scope = value(document, dependency + "/scope");
    assertEquals("compile", scope);
  }

  private static String value(final Document document, final String expression) throws Exception {
    return XPathFactory.newInstance().newXPath().evaluate(expression, document);
  }
}
