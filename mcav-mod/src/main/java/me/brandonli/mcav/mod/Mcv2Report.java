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

import com.google.common.base.MoreObjects;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

final class Mcv2Report {

  private static final byte VERSION = 1;

  private static final byte FLAG_SET = 1;
  private static final byte FLAG_CLEAR = 0;

  private final boolean irisPresent;
  private final ShaderPack shaderPack;
  private final boolean decodesUnderShaders;

  private Mcv2Report(final boolean irisPresent, final ShaderPack shaderPack, final boolean decodesUnderShaders) {
    this.irisPresent = irisPresent;
    this.shaderPack = shaderPack;
    this.decodesUnderShaders = decodesUnderShaders;
  }

  static Mcv2Report of(final boolean irisPresent, final ShaderPack shaderPack, final boolean decodesUnderShaders) {
    return new Mcv2Report(irisPresent, shaderPack, decodesUnderShaders);
  }

  byte[] toBytes() {
    final byte iris = this.flag(this.irisPresent);
    final byte code = this.shaderPack.code();
    final byte decodes = this.flag(this.decodesUnderShaders);
    return new byte[] { VERSION, iris, code, decodes };
  }

  private byte flag(final boolean set) {
    return set ? FLAG_SET : FLAG_CLEAR;
  }

  boolean isIrisPresent() {
    return this.irisPresent;
  }

  ShaderPack getShaderPack() {
    return this.shaderPack;
  }

  boolean canDecodeUnderShaders() {
    return this.decodesUnderShaders;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Mcv2Report report)) {
      return false;
    }

    return (
      this.irisPresent == report.irisPresent &&
      this.shaderPack == report.shaderPack &&
      this.decodesUnderShaders == report.decodesUnderShaders
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.irisPresent, this.shaderPack, this.decodesUnderShaders);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("irisPresent", this.irisPresent)
      .add("shaderPack", this.shaderPack)
      .add("decodesUnderShaders", this.decodesUnderShaders)
      .toString();
  }
}
