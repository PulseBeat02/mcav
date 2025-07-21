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
package me.brandonli.util;

public final class GLTextureStreamer {

  public static boolean streamTextureToAllTVs(final int glTextureId, final int width, final int height) {
    if (glTextureId < 0) {
      return false;
    }

    GlobalTextureState.setGlobalTexture(glTextureId, width, height);
    return true;
  }

  public static boolean clearTextureFromAllTVs() {
    GlobalTextureState.clearGlobalTexture();
    return true;
  }

  public static boolean isGloballyStreaming() {
    return GlobalTextureState.isStreaming();
  }

  public static int getCurrentGlTextureId() {
    return GlobalTextureState.getCurrentGlTextureId();
  }

  public static int getCurrentTextureWidth() {
    return GlobalTextureState.getTextureWidth();
  }

  public static int getCurrentTextureHeight() {
    return GlobalTextureState.getTextureHeight();
  }

  private GLTextureStreamer() {}
}
