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

import me.brandonli.Mcav;
import net.minecraft.util.Identifier;

public final class GlobalTextureState {

  private static final Identifier DEFAULT_TEXTURE_ID = Identifier.of(Mcav.MOD_ID, "global_tv_texture");
  private static int currentGlTextureId = -1;
  private static int textureWidth = 0;
  private static int textureHeight = 0;
  private static boolean isStreaming = false;

  public static void setGlobalTexture(final int glTextureId, final int width, final int height) {
    currentGlTextureId = glTextureId;
    textureWidth = width;
    textureHeight = height;
    isStreaming = glTextureId >= 0;
  }

  public static void clearGlobalTexture() {
    currentGlTextureId = -1;
    textureWidth = 0;
    textureHeight = 0;
    isStreaming = false;
  }

  public static int getCurrentGlTextureId() {
    return currentGlTextureId;
  }

  public static int getTextureWidth() {
    return textureWidth;
  }

  public static int getTextureHeight() {
    return textureHeight;
  }

  public static boolean isStreaming() {
    return isStreaming;
  }

  public static Identifier getTextureIdentifier() {
    return DEFAULT_TEXTURE_ID;
  }

  private GlobalTextureState() {}
}
