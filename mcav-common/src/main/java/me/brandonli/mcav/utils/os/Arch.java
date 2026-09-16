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
package me.brandonli.mcav.utils.os;

/**
 * The processor family of a machine, as far as the installers care: which binaries to download.
 */
public enum Arch {
  /**
   * Intel and AMD processors, 32-bit or 64-bit.
   */
  X86,
  /**
   * ARM processors such as Apple silicon and Raspberry Pi, 32-bit or 64-bit.
   */
  ARM,
  /**
   * Any other processor family, such as RISC-V, POWER ({@code ppc64le}), IBM Z ({@code s390x}) or LoongArch. The
   * library never downloads programs for it, because none of the builds it knows can run there.
   */
  OTHER,
}
