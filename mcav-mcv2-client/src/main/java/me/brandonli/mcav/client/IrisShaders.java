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
package me.brandonli.mcav.client;

import net.irisshaders.iris.api.v0.IrisApi;

/** Asks Iris, through its public API, whether it draws a shader pack. */
final class IrisShaders {

  /** The mod id of Iris, on both loaders. */
  static final String MOD_ID = "iris";

  private final boolean installed;

  private boolean apiMissing;

  /**
   * Constructs the question.
   *
   * @param installed whether the loader has Iris: without it, nothing of Iris is touched
   */
  IrisShaders(final boolean installed) {
    this.installed = installed;
  }

  /** What to tell the server now. */
  Mcv2Report report() {
    if (!this.installed) {
      return new Mcv2Report(false, ShaderPack.NONE, false);
    }
    if (!this.apiMissing) {
      try {
        final boolean inUse = IrisApi.getInstance().isShaderPackInUse();
        return new Mcv2Report(true, inUse ? ShaderPack.IN_USE : ShaderPack.NONE, false);
      } catch (final LinkageError missing) {
        // an Iris built without the API, or with another version of it, never gains it while the game runs
        this.apiMissing = true;
      }
    }
    return new Mcv2Report(true, ShaderPack.UNKNOWN, false);
  }
}
