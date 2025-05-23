/*
 * This file is part of mcav, a media playback library for Minecraft
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
 * Enum representing different types of artifacts, each associated with a specific artifact ID.
 */
public enum Artifact {
  /**
   * Represents the common artifact, identified by the artifact ID "mcav-common".
   */
  COMMON("mcav-common"),

  /**
   * Represents the JDA artifact, identified by the artifact ID "mcav-jda".
   * This artifact is associated with the JDA functionality within the mcav project.
   */
  JDA("mcav-jda"),

  /**
   * Represents the HTTP artifact, identified by the artifact ID "mcav-http".
   */
  HTTP("mcav-http");

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
