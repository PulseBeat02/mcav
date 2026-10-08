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

import java.util.function.BooleanSupplier;
import net.irisshaders.iris.api.v0.IrisApi;

/** Asks Iris, through its public API, whether it draws a shader pack. */
final class IrisShaders {

  /** The mod id of Iris, on both loaders. */
  static final String MOD_ID = "iris";

  /**
   * The Iris version MCV2 was proven to decode under ({@link ShaderDecoder}): its versions read "1.11.7+mc26.3" on Fabric
   * and NeoForge alike.
   */
  static final String TESTED_VERSION = "1.11.7";

  private final boolean installed;

  private final BooleanSupplier decodesUnderShaders;

  private boolean apiMissing;

  /**
   * Constructs the question.
   *
   * @param installed           whether the loader has Iris: without it, nothing of Iris is touched
   * @param decodesUnderShaders whether MCV2 decodes under a shader pack in this client
   */
  IrisShaders(final boolean installed, final BooleanSupplier decodesUnderShaders) {
    this.installed = installed;
    this.decodesUnderShaders = decodesUnderShaders;
  }

  /** Whether an Iris version is the one MCV2 was proven to decode under, whatever the build metadata after a '+'. */
  static boolean isTested(final String version) {
    return version.equals(TESTED_VERSION) || version.startsWith(TESTED_VERSION + "+");
  }

  /** What to tell the server now. */
  Mcv2Report report() {
    if (!this.installed) {
      return new Mcv2Report(false, ShaderPack.NONE, false);
    }
    if (!this.apiMissing) {
      try {
        final boolean inUse = IrisApi.getInstance().isShaderPackInUse();
        return inUse
          ? new Mcv2Report(true, ShaderPack.IN_USE, this.decodesUnderShaders.getAsBoolean())
          : new Mcv2Report(true, ShaderPack.NONE, false);
      } catch (final LinkageError missing) {
        // an Iris built without the API, or with another version of it, never gains it while the game runs
        this.apiMissing = true;
      }
    }
    return new Mcv2Report(true, ShaderPack.UNKNOWN, false);
  }
}
