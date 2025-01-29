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

/**
 * Enum representing different artifacts in the mcav project.
 */
public enum Artifact {
  /**
   * Represents the common artifact, containing the core functionality of the mcav project.
   */
  COMMON("mcav-common"),

  /**
   * Represents the JDA artifact, which provides integration with the JDA (Java Discord API).
   */
  JDA("mcav-jda"),

  /**
   * Represents the HTTP artifact, which provides HTTP audio streaming.
   */
  HTTP("mcav-http"),

  BROWSER("mcav-browser"),

  VM("mcav-vm"),

  VNC("mcav-vnc");

  private final String artifactId;

  Artifact(final String artifactId) {
    this.artifactId = artifactId;
  }

  /**
   * Returns the artifact ID of this artifact.
   *
   * @return the artifact ID
   */
  public String getArtifactId() {
    return this.artifactId;
  }
}
