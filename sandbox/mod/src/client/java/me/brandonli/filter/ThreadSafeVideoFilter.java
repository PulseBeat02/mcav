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
import me.brandonli.mcav.media.player.pipeline.filter.FunctionalVideoFilter;
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
  private int glTextureId = -1;
  private boolean isStarted = false;

  @Override
  public void start() {
    if (isStarted) return;

    // Create GL texture on main thread
    MinecraftClient.getInstance()
      .execute(() -> {
        glTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
      });

    isStarted = true;
  }

  @Override
  public void release() {
    if (!isStarted) return;

    // Delete GL texture on main thread
    if (glTextureId >= 0) {
      final int textureToDelete = glTextureId;
      MinecraftClient.getInstance()
        .execute(() -> {
          GL11.glDeleteTextures(textureToDelete);
        });
      glTextureId = -1;
    }

    frameQueue.clear();
    isStarted = false;
  }

  @Override
  public void applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    if (!isStarted || samples == null) {
      return;
    }

    // Queue the frame data for processing on main thread (runs on VLC worker thread)
    final FrameData frameData = new FrameData(samples);
    frameQueue.offer(frameData);

    // Schedule processing on main thread if not already processing
    if (isProcessing.compareAndSet(false, true)) {
      MinecraftClient.getInstance().execute(this::processQueuedFrames);
    }
  }

  private void processQueuedFrames() {
    try {
      if (glTextureId < 0) return;

      // Process all queued frames on main thread with GL context
      FrameData frameData;
      FrameData latestFrame = null;

      // Get the latest frame (drop intermediate frames for performance)
      while ((frameData = frameQueue.poll()) != null) {
        latestFrame = frameData;
      }

      if (latestFrame != null) {
        final ImageBuffer imageBuffer = latestFrame.imageBuffer;
        final ByteBuffer pixelData = imageBuffer.getData();
        final int width = imageBuffer.getWidth();
        final int height = imageBuffer.getHeight();

        // Upload texture data to GPU
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, width, height, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixelData);

        // Update global texture state
        GLTextureStreamer.streamTextureToAllTVs(glTextureId, width, height);
      }
    } finally {
      isProcessing.set(false);

      // If more frames were queued while we were processing, schedule another run
      if (!frameQueue.isEmpty() && isProcessing.compareAndSet(false, true)) {
        MinecraftClient.getInstance().execute(this::processQueuedFrames);
      }
    }
  }

  public int getTextureId() {
    return glTextureId;
  }
}
