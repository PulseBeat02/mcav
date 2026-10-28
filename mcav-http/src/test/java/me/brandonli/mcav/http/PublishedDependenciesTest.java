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

import java.io.File;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.apache.catalina.startup.Tomcat;
import org.apache.el.lang.ELSupport;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

final class PublishedDependenciesTest {

  private static final String TOMCAT_GROUP = "org.apache.tomcat.embed";
  private static final String JACKSON_CORE_GROUP = "tools.jackson.core";

  private static final String JACKSON_BILL_OF_MATERIALS_PATH =
    "/project/dependencyManagement/dependencies/dependency[groupId='tools.jackson' and artifactId='jackson-bom']";

  private static final String TYPE_PATH = "/type";
  private static final String SCOPE_PATH = "/scope";
  private static final String VERSION_PATH = "/version";

  @Test
  void shouldPublishTheDependencyVersionsUsedByTheLibrary() throws Exception {
    final Document document = this.readPublishedPom();
    this.assertPublishedVersion(document, Tomcat.class, TOMCAT_GROUP, "tomcat-embed-core");
    this.assertPublishedVersion(document, ELSupport.class, TOMCAT_GROUP, "tomcat-embed-el");
    this.assertPublishedVersion(document, WsSci.class, TOMCAT_GROUP, "tomcat-embed-websocket");
    this.assertPublishedVersion(document, JsonParser.class, JACKSON_CORE_GROUP, "jackson-core");
    this.assertPublishedVersion(document, ObjectMapper.class, JACKSON_CORE_GROUP, "jackson-databind");
  }

  private Document readPublishedPom() throws Exception {
    final String path = System.getProperty("mcav.published.pom");
    assertNotNull(path, "the build supplies the generated publication POM");

    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    final DocumentBuilder builder = factory.newDocumentBuilder();
    final Path pom = Path.of(path);
    final File file = pom.toFile();
    return builder.parse(file);
  }

  private void assertPublishedVersion(final Document document, final Class<?> type, final String group, final String artifact)
    throws Exception {
    final String version = this.findImplementationVersion(type);
    assertNotNull(version, "the resolved library declares its implementation version");

    final String dependency = "/project/dependencies/dependency[groupId='%s' and artifactId='%s']".formatted(group, artifact);
    final String publishedVersion = this.evaluate(document, dependency + VERSION_PATH);
    final String message = "The resolved version of %s is what Maven consumers need".formatted(artifact);
    assertEquals(version, publishedVersion, message);

    final String scope = this.evaluate(document, dependency + SCOPE_PATH);
    assertEquals("compile", scope);
  }

  private String findImplementationVersion(final Class<?> type) {
    final Package typePackage = type.getPackage();
    return typePackage.getImplementationVersion();
  }

  private String evaluate(final Document document, final String expression) throws Exception {
    final XPathFactory factory = XPathFactory.newInstance();
    final XPath xpath = factory.newXPath();
    return xpath.evaluate(expression, document);
  }

  @Test
  void shouldImportTheJacksonBillOfMaterials() throws Exception {
    final Document document = this.readPublishedPom();
    final String type = this.evaluate(document, JACKSON_BILL_OF_MATERIALS_PATH + TYPE_PATH);
    assertEquals("pom", type);

    final String scope = this.evaluate(document, JACKSON_BILL_OF_MATERIALS_PATH + SCOPE_PATH);
    assertEquals("import", scope);

    final String version = this.evaluate(document, JACKSON_BILL_OF_MATERIALS_PATH + VERSION_PATH);
    final String expectedVersion = this.findImplementationVersion(ObjectMapper.class);
    assertEquals(expectedVersion, version);
  }
}
