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
package me.brandonli.mcav.lwjgl;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.FunctionalVideoFilter;
import org.lwjgl.opengl.GL11;

/**
 * A simple OpenGL texture filter that creates a texture and applies it to the video frames.
 */
public class GLTextureFilter implements FunctionalVideoFilter {

  private int textureId;

  /**
   * Constructs a GLTextureFilter with an uninitialized texture ID.
   */
  public GLTextureFilter() {
    this(-1);
  }

  /**
   * Constructs a GLTextureFilter with a specified texture ID.
   *
   * @param textureId the OpenGL texture ID to use
   */
  public GLTextureFilter(final int textureId) {
    this.textureId = textureId;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    this.textureId = GL11.glGenTextures();
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    if (this.textureId != -1) {
      GL11.glDeleteTextures(this.textureId);
      this.textureId = -1;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    if (this.textureId == -1) {
      return;
    }
    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final ByteBuffer buffer = samples.getData();
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
  }

  /**
   * Gets the OpenGL texture ID.
   *
   * @return the texture ID
   */
  public int getTextureId() {
    return this.textureId;
  }
}
