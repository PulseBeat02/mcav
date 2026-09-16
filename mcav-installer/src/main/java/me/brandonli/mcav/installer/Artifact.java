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
 * The modules of the library that can be downloaded at runtime. Each module brings its own transitive
 * dependencies; {@link #COMMON} is required by every other module.
 */
public enum Artifact {
  /**
   * The core library: players, filters, dithering, and installers.
   */
  COMMON("mcav-common"),
  /**
   * Discord voice output through JDA.
   */
  JDA("mcav-jda"),
  /**
   * The web audio player served over HTTP.
   */
  HTTP("mcav-http"),
  /**
   * Web page streaming through Selenium and Playwright.
   */
  BROWSER("mcav-browser"),
  /**
   * Virtual machines through QEMU.
   */
  VM("mcav-vm"),
  /**
   * Remote desktops through VNC.
   */
  VNC("mcav-vnc"),
  /**
   * Positional audio through Simple Voice Chat.
   */
  SVC("mcav-svc"),
  /**
   * OpenGL texture output through LWJGL.
   */
  LWJGL("mcav-lwjgl"),
  /**
   * Minecraft Paper integration: maps, blocks, entities, chat, scoreboards, and resource packs.
   */
  BUKKIT("mcav-bukkit");

  /**
   * The Maven group of every module.
   */
  public static final String GROUP_ID = "me.brandonli";

  /**
   * The version downloaded when none is specified.
   */
  public static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

  private final String artifactId;

  Artifact(final String artifactId) {
    this.artifactId = artifactId;
  }

  /**
   * Gets the Maven artifact id of the module.
   *
   * @return the artifact id, such as {@code mcav-common}
   */
  public String getArtifactId() {
    return this.artifactId;
  }
}
