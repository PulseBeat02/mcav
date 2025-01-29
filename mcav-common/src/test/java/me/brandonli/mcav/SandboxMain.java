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
package me.brandonli.mcav;

public final class SandboxMain {

  public static void main(final String[] args) {
    System.out.println(System.getProperty("java.library.path"));
    System.out.println(System.getenv());
    System.load(
      "C:\\Users\\brand\\.javacpp\\cache\\mcav-minecraft-all-1.0.0-20250504.225657-5-all.jar\\org\\bytedeco\\openblas\\windows-x86_64\\libopenblas_nolapack.dll"
    );
  }
}
