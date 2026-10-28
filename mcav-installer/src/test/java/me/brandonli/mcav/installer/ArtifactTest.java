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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ArtifactTest {

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
}
