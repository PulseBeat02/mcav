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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Artifact}, {@link InstallationException} and {@link JarInjectorException}.
 */
final class ArtifactTest {

  @Test
  void listsEveryModuleWithItsArtifactId() {
    final Artifact[] values = Artifact.values();
    final List<String> artifactIds = new ArrayList<>();
    for (final Artifact artifact : values) {
      final String artifactId = artifact.getArtifactId();
      artifactIds.add(artifactId);
    }
    final List<String> expected = List.of(
      "mcav-common",
      "mcav-jda",
      "mcav-http",
      "mcav-browser",
      "mcav-vm",
      "mcav-vnc",
      "mcav-svc",
      "mcav-lwjgl",
      "mcav-bukkit"
    );
    assertEquals(expected, artifactIds);
    final Artifact common = Artifact.valueOf("COMMON");
    assertSame(Artifact.COMMON, common);
    assertThrows(IllegalArgumentException.class, () -> Artifact.valueOf("UNKNOWN"));
  }

  @Test
  void usesTheGroupAndVersionOfThePublishedModules() {
    assertEquals("me.brandonli", Artifact.GROUP_ID);
    assertEquals("1.0.0-SNAPSHOT", Artifact.DEFAULT_VERSION);
  }

  @Test
  void installationExceptionsKeepTheMessage() {
    final InstallationException exception = new InstallationException("cannot copy");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("cannot copy", message);
    assertNull(cause);
  }

  @Test
  void installationExceptionsKeepTheMessageAndTheCause() {
    final IOException copyFailure = new IOException("disk full");
    final InstallationException exception = new InstallationException("cannot copy", copyFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("cannot copy", message);
    assertSame(copyFailure, cause);
  }

  @Test
  void installationExceptionsAreRuntimeExceptions() {
    final InstallationException exception = new InstallationException("cannot copy");
    assertInstanceOf(RuntimeException.class, exception, "a failed installation is recoverable, so it is no Error");
  }

  @Test
  void injectorExceptionsCarryMessageAndCause() {
    final IllegalAccessException cause = new IllegalAccessException("closed module");
    final JarInjectorException withCause = new JarInjectorException("cannot inject", cause);
    final JarInjectorException withoutCause = new JarInjectorException("cannot inject");
    final String message = withCause.getMessage();
    final Throwable actualCause = withCause.getCause();
    final String otherMessage = withoutCause.getMessage();
    final Throwable missingCause = withoutCause.getCause();
    assertEquals("cannot inject", message);
    assertSame(cause, actualCause);
    assertEquals("cannot inject", otherMessage);
    assertNull(missingCause);
    assertInstanceOf(UnsupportedOperationException.class, withCause);
  }
}
