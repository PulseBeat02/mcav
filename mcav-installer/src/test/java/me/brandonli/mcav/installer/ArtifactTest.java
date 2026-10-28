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
package me.brandonli.mcav.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ArtifactTest {

  private static final String COPY_FAILURE_MESSAGE = "Jars cannot be copied!";

  @Test
  void shouldListEveryModuleWithItsArtifactId() {
    final Artifact[] values = Artifact.values();
    final List<String> artifactIds = Arrays.stream(values).map(Artifact::getArtifactId).toList();

    final List<String> expected = List.of(
      "mcav-common",
      "mcav-discord",
      "mcav-http",
      "mcav-browser",
      "mcav-vm",
      "mcav-vnc",
      "mcav-voicechat",
      "mcav-lwjgl",
      "mcav-bukkit"
    );
    assertEquals(expected, artifactIds);
  }

  @Test
  void shouldUseTheGroupAndVersionOfThePublishedModules() {
    assertEquals("me.brandonli", Artifact.GROUP_ID);
    assertEquals("1.0.0-SNAPSHOT", Artifact.DEFAULT_VERSION);
  }

  @Test
  void shouldKeepTheMessageOfInstallationExceptions() {
    final InstallationException exception = new InstallationException(COPY_FAILURE_MESSAGE);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals(COPY_FAILURE_MESSAGE, message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseOfInstallationExceptions() {
    final IOException copyFailure = new IOException("Disk is full!");
    final InstallationException exception = new InstallationException(COPY_FAILURE_MESSAGE, copyFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals(COPY_FAILURE_MESSAGE, message);
    assertSame(copyFailure, cause);
  }

  @Test
  void shouldMakeInstallationExceptionsRuntimeExceptions() {
    final InstallationException exception = new InstallationException(COPY_FAILURE_MESSAGE);
    assertInstanceOf(RuntimeException.class, exception, "a failed installation is recoverable, so it is no Error");
  }

  @Test
  void shouldCarryTheMessageAndCauseOfInjectorExceptions() {
    final IllegalAccessException cause = new IllegalAccessException("Module is closed!");
    final JarInjectorException withCause = new JarInjectorException("Jars cannot be injected!", cause);
    final JarInjectorException withoutCause = new JarInjectorException("Jars cannot be injected!");
    final String message = withCause.getMessage();
    final Throwable actualCause = withCause.getCause();
    final String otherMessage = withoutCause.getMessage();
    final Throwable missingCause = withoutCause.getCause();
    assertEquals("Jars cannot be injected!", message);
    assertSame(cause, actualCause);
    assertEquals("Jars cannot be injected!", otherMessage);
    assertNull(missingCause);
    assertInstanceOf(UnsupportedOperationException.class, withCause);
  }
}
