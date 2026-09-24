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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Streams the frames of a pipeline into an OpenGL texture.
 *
 * <p>OpenGL may only be called from the thread that owns the context, but filters run on the thread of the
 * player. The filter therefore only copies each frame into a staging buffer; the render thread uploads the newest
 * frame by calling {@link #upload()} once per rendered frame. {@link #start()} and {@link #release()} must also be
 * called on the render thread. Two staging buffers are used, so the player copies the next frame while the render
 * thread uploads the previous one and neither waits for the other.
 *
 * <pre><code>
 *   // render thread, context current
 *   final GLTextureFilter texture = new GLTextureFilter();
 *   texture.start();
 *   final VideoAttachableCallback video = player.getVideoAttachableCallback();
 *   video.attach(VideoPipelineStep.of(texture));
 *   while (running) {
 *     texture.upload();
 *     GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
 *     // draw a textured quad
 *   }
 *   texture.release();
 * </code></pre>
 *
 * <p>Frames are uploaded as {@code GL_BGR}, the pixel layout of the pipeline, so no conversion is needed; the
 * texture uses the {@code GL_RGB8} internal format. The OpenGL state of the caller is left as it was: the bound
 * texture and the unpack settings are restored after every call, and the unpack settings are reset for the upload
 * itself, so a row length or skip set by the caller never distorts a frame. A pixel unpack buffer must not be bound
 * while {@link #upload()} runs.
 */
public class GLTextureFilter implements FunctionalVideoFilter {

  private static final int NO_TEXTURE = 0;
  private static final int CHANNELS = 3;

  private final Object lock;
  private final boolean ownsTexture;

  private int textureId;
  private int textureWidth;
  private int textureHeight;
  private ByteBuffer backBuffer;
  private ByteBuffer frontBuffer;
  private int stagedWidth;
  private int stagedHeight;
  private boolean dirty;

  /**
   * Constructs a filter that creates its own texture in {@link #start()}.
   */
  public GLTextureFilter() {
    this.lock = new Object();
    this.ownsTexture = true;
    this.textureId = NO_TEXTURE;
    this.backBuffer = ByteBuffer.allocateDirect(0);
    this.frontBuffer = ByteBuffer.allocateDirect(0);
  }

  /**
   * Constructs a filter that streams into an existing texture, which the caller keeps owning.
   *
   * @param textureId the name of the texture, created with {@code glGenTextures}
   */
  public GLTextureFilter(final int textureId) {
    Preconditions.checkArgument(textureId > 0, "Texture id must be positive but was %s", textureId);
    this.lock = new Object();
    this.ownsTexture = false;
    this.textureId = textureId;
    this.backBuffer = ByteBuffer.allocateDirect(0);
    this.frontBuffer = ByteBuffer.allocateDirect(0);
  }

  /**
   * Creates the texture with linear filtering and edge clamping. Call it on the render thread.
   *
   * @throws IllegalStateException if no OpenGL context is current on the calling thread
   */
  @Override
  public void start() {
    requireContext();
    if (this.ownsTexture && this.textureId == NO_TEXTURE) {
      this.textureId = GL11.glGenTextures();
      this.textureWidth = 0;
      this.textureHeight = 0;
    }

    final int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
  }

  /**
   * Deletes the texture if the filter created it. Call it on the render thread.
   *
   * @throws IllegalStateException if the filter created a texture and no OpenGL context is current on the calling
   *                               thread
   */
  @Override
  public void release() {
    if (this.ownsTexture && this.textureId != NO_TEXTURE) {
      requireContext();
      GL11.glDeleteTextures(this.textureId);
      this.textureId = NO_TEXTURE;
    }
    synchronized (this.lock) {
      this.dirty = false;
      this.backBuffer = ByteBuffer.allocateDirect(0);
      this.frontBuffer = ByteBuffer.allocateDirect(0);
    }
  }

  /**
   * Copies the frame into the staging buffer. Runs on the player thread and makes no OpenGL calls.
   *
   * @param samples  the frame
   * @param metadata the metadata of the frame
   * @return always false, because the frame is only copied into the staging buffer and never modified
   * @throws NullPointerException if the frame or the metadata is null
   */
  @Override
  public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(samples, "Frame must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");

    final int width = samples.getWidth();
    final int height = samples.getHeight();
    final ByteBuffer data = samples.getData();
    final ByteBuffer source = data.duplicate();
    final int size = width * height * CHANNELS;
    if (source.remaining() < size) {
      return false;
    }

    final int start = source.position();
    final int end = start + size;
    source.limit(end);
    this.stage(source, width, height);
    return false;
  }

  /**
   * Copies a frame into the back buffer and marks it as the newest frame, growing the back buffer when it is too
   * small.
   *
   * @param source the pixels of the frame, positioned and limited to exactly the frame
   * @param width  the width of the frame
   * @param height the height of the frame
   */
  private void stage(final ByteBuffer source, final int width, final int height) {
    final int size = source.remaining();
    synchronized (this.lock) {
      if (this.backBuffer.capacity() < size) {
        this.backBuffer = ByteBuffer.allocateDirect(size);
      }
      this.backBuffer.clear();
      this.backBuffer.put(source);
      this.backBuffer.flip();
      this.stagedWidth = width;
      this.stagedHeight = height;
      this.dirty = true;
    }
  }

  /**
   * Uploads the newest frame to the texture if one arrived since the last call. Call it on the render thread,
   * once per rendered frame. The frame is uploaded without holding the lock the player needs, so the player is never
   * blocked by the upload.
   *
   * @return true if a new frame was uploaded
   * @throws IllegalStateException if {@link #start()} was not called, or no OpenGL context is current on the calling
   *                               thread
   */
  public boolean upload() {
    if (this.textureId == NO_TEXTURE) {
      throw new IllegalStateException("The texture was not created; call start() on the render thread first");
    }
    requireContext();

    final ByteBuffer pixels;
    final int width;
    final int height;
    synchronized (this.lock) {
      if (!this.dirty) {
        return false;
      }
      // the staged frame becomes the front buffer, and the player copies the next frame into the other one
      pixels = this.backBuffer;
      this.backBuffer = this.frontBuffer;
      this.frontBuffer = pixels;
      width = this.stagedWidth;
      height = this.stagedHeight;
      this.dirty = false;
    }
    this.transfer(pixels, width, height);
    return true;
  }

  /**
   * Uploads pixels to the texture, leaving the bound texture and the unpack settings of the caller as they were.
   *
   * @param pixels the frame in {@code GL_BGR} layout
   * @param width  the width of the frame
   * @param height the height of the frame
   */
  @VisibleForTesting
  void transfer(final ByteBuffer pixels, final int width, final int height) {
    final int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    final int previousAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
    final int previousRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
    final int previousSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
    final int previousSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);

    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
      // Rows of 3-byte pixels are not usually 4-byte aligned; the frame is tightly packed.
      setUnpackState(1, 0, 0, 0);
      this.writePixels(pixels, width, height);
    } finally {
      setUnpackState(previousAlignment, previousRowLength, previousSkipRows, previousSkipPixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
    }
  }

  /**
   * Writes pixels into the bound texture, reallocating the texture storage when the frame size changed.
   *
   * @param pixels the frame in {@code GL_BGR} layout
   * @param width  the width of the frame
   * @param height the height of the frame
   */
  private void writePixels(final ByteBuffer pixels, final int width, final int height) {
    final boolean resized = width != this.textureWidth || height != this.textureHeight;
    if (resized) {
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, width, height, 0, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, pixels);
      this.textureWidth = width;
      this.textureHeight = height;
    } else {
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, pixels);
    }
  }

  /**
   * Sets the pixel unpack settings of the current context.
   *
   * @param alignment  the row alignment in bytes
   * @param rowLength  the row length in pixels, or 0 for the width of the upload
   * @param skipRows   the number of rows to skip
   * @param skipPixels the number of pixels to skip in every row
   */
  private static void setUnpackState(final int alignment, final int rowLength, final int skipRows, final int skipPixels) {
    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
  }

  /**
   * Checks that an OpenGL context is current, since OpenGL calls without one crash the JVM or fail obscurely.
   *
   * @throws IllegalStateException if no OpenGL context is current on the calling thread
   */
  private static void requireContext() {
    try {
      final GLCapabilities capabilities = GL.getCapabilities();
      Preconditions.checkNotNull(capabilities, "OpenGL capabilities must be set");
    } catch (final IllegalStateException exception) {
      throw new IllegalStateException(
        "No OpenGL context is current on this thread; call start(), upload() and release() on the render thread " +
        "after making the context current and calling GL.createCapabilities()",
        exception
      );
    }
  }

  /**
   * Checks whether a frame is waiting to be uploaded.
   *
   * @return true if {@link #upload()} would upload a frame
   */
  public boolean hasPendingFrame() {
    synchronized (this.lock) {
      return this.dirty;
    }
  }

  /**
   * Gets the name of the texture.
   *
   * @return the texture name, or 0 before {@link #start()}
   */
  public int getTextureId() {
    return this.textureId;
  }

  /**
   * Gets the width of the texture, which is the width of the last uploaded frame.
   *
   * @return the width in pixels, or 0 before the first upload
   */
  public int getWidth() {
    return this.textureWidth;
  }

  /**
   * Gets the height of the texture, which is the height of the last uploaded frame.
   *
   * @return the height in pixels, or 0 before the first upload
   */
  public int getHeight() {
    return this.textureHeight;
  }
}
