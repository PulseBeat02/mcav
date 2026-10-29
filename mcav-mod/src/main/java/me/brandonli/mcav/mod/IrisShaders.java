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
package me.brandonli.mcav.mod;

import java.util.function.BooleanSupplier;
import net.irisshaders.iris.api.v0.IrisApi;

final class IrisShaders {

  static final String MOD_ID = "iris";

  // MCV2 was proven to decode under it; its versions read "1.11.7+mc26.3" on both loaders
  private static final String TESTED_VERSION = "1.11.7";

  private static final String BUILD_METADATA_SEPARATOR = "+";

  private final boolean installed;

  private final BooleanSupplier decodesUnderShaders;

  private boolean apiMissing;

  // without Iris installed, nothing of Iris is touched
  IrisShaders(final boolean installed, final BooleanSupplier decodesUnderShaders) {
    this.installed = installed;
    this.decodesUnderShaders = decodesUnderShaders;
  }

  // ignores the build metadata after a '+'
  static boolean isTested(final String version) {
    return version.equals(TESTED_VERSION) || version.startsWith(TESTED_VERSION + BUILD_METADATA_SEPARATOR);
  }

  Mcv2Report report() {
    if (!this.installed) {
      final boolean irisPresent = false;
      final boolean decodesUnderShaders = false;
      return Mcv2Report.of(irisPresent, ShaderPack.NONE, decodesUnderShaders);
    }

    if (!this.apiMissing) {
      try {
        return this.reportShaderPack();
      } catch (final LinkageError exception) {
        this.apiMissing = true;
      }
    }

    final boolean irisPresent = true;
    final boolean decodesUnderShaders = false;
    return Mcv2Report.of(irisPresent, ShaderPack.UNKNOWN, decodesUnderShaders);
  }

  private Mcv2Report reportShaderPack() {
    final IrisApi iris = IrisApi.getInstance();
    final boolean inUse = iris.isShaderPackInUse();
    final boolean irisPresent = true;
    if (!inUse) {
      final boolean decodesUnderShaders = false;
      return Mcv2Report.of(irisPresent, ShaderPack.NONE, decodesUnderShaders);
    }

    final boolean decodesUnderShaders = this.decodesUnderShaders.getAsBoolean();
    return Mcv2Report.of(irisPresent, ShaderPack.IN_USE, decodesUnderShaders);
  }
}
