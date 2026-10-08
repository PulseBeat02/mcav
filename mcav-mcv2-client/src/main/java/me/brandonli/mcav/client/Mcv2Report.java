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

/**
 * What the mod tells the server: version 1 of a report is four bytes, the version, 1 if Iris is installed, the shader
 * pack's code, and 1 if MCV2 decodes under the shader pack in use.
 *
 * @param irisPresent         whether Iris is installed
 * @param shaderPack          what Iris says of its shader pack
 * @param decodesUnderShaders whether MCV2 decodes under the shader pack
 */
record Mcv2Report(boolean irisPresent, ShaderPack shaderPack, boolean decodesUnderShaders) {
  /** The version of the reports this mod sends. */
  static final byte VERSION = 1;

  /** The report as it is sent. */
  byte[] toBytes() {
    return new byte[] { VERSION, flag(this.irisPresent), this.shaderPack.code(), flag(this.decodesUnderShaders) };
  }

  private static byte flag(final boolean set) {
    return (byte) (set ? 1 : 0);
  }
}
