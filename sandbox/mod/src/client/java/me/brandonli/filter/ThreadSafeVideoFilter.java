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
package me.brandonli.filter;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.util.GLTextureStreamer;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;

public class ThreadSafeVideoFilter implements FunctionalVideoFilter {

  private static class FrameData {

    final ImageBuffer imageBuffer;

    FrameData(final ImageBuffer imageBuffer) {
      this.imageBuffer = imageBuffer;
    }
  }

  private final ConcurrentLinkedQueue<FrameData> frameQueue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean isProcessing = new AtomicBoolean(false);

  private volatile int glTextureId = -1;
  private volatile boolean isStarted = false;

  @Override
  public void start() {
    if (this.isStarted) {
      return;
    }

    MinecraftClient.getInstance()
      .execute(() -> {
        this.glTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.glTextureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
      });

    this.isStarted = true;
  }

  @Override
  public void release() {
    if (!this.isStarted) {
      return;
    }

    if (this.glTextureId >= 0) {
      final int textureToDelete = this.glTextureId;
      MinecraftClient.getInstance()
        .execute(() -> {
          GL11.glDeleteTextures(textureToDelete);
        });
      this.glTextureId = -1;
    }

    this.frameQueue.clear();
    this.isStarted = false;
  }

  @Override
  public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    if (!this.isStarted || samples == null) {
      return false;
    }

    final FrameData frameData = new FrameData(samples);
    this.frameQueue.offer(frameData);

    if (this.isProcessing.compareAndSet(false, true)) {
      MinecraftClient.getInstance().execute(this::processQueuedFrames);
    }
    return true;
  }

  private void processQueuedFrames() {
    try {
      if (this.glTextureId < 0) {
        return;
      }

      FrameData frameData;
      FrameData latestFrame = null;

      while ((frameData = this.frameQueue.poll()) != null) {
        latestFrame = frameData;
      }

      if (latestFrame != null) {
        final ImageBuffer imageBuffer = latestFrame.imageBuffer;
        final ByteBuffer pixelData = imageBuffer.getData();
        final int width = imageBuffer.getWidth();
        final int height = imageBuffer.getHeight();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.glTextureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, width, height, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixelData);

        GLTextureStreamer.streamTextureToAllTVs(this.glTextureId, width, height);
      }
    } finally {
      this.isProcessing.set(false);

      if (!this.frameQueue.isEmpty() && this.isProcessing.compareAndSet(false, true)) {
        MinecraftClient.getInstance().execute(this::processQueuedFrames);
      }
    }
  }

  public int getTextureId() {
    return this.glTextureId;
  }
}
