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
package me.brandonli.mcav.media.player.vnc;

import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.rendering.ColorDepth;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.source.VNCSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Implementation of the {@link VNCPlayer} interface that provides functionality
 * to start, pause, resume, and release a Virtual Network Computing (VNC) player.
 * This class is thread-safe and manages the lifecycle of a VNC client and associated
 * video processing pipeline steps.
 * <p>
 * The class uses an {@link ExecutorService} for frame capturing and processing, with
 * distinct executors for these tasks. Frames received from the VNC server are processed
 * synchronously using a video processing pipeline.
 */
public class VNCPlayerImpl implements VNCPlayer {

  private final ExecutorService frameRetrieverExecutor;
  private final ExecutorService frameProcessorExecutor;
  private final BlockingQueue<BufferedImage> frameBuffer;

  private final Object lock = new Object();
  private final int bufferCapacity = 100;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private boolean connected;
  private @Nullable CompletableFuture<?> processingFuture;
  private @Nullable VernacularClient vncClient;

  private VideoPipelineStep videoPipeline;
  private VideoMetadata videoMetadata;
  private long sleepTime;

  VNCPlayerImpl() {
    this.frameRetrieverExecutor = Executors.newSingleThreadExecutor();
    this.frameProcessorExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    this.frameBuffer = new LinkedBlockingQueue<>(this.bufferCapacity);
    this.connected = false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final VNCSource source) throws Exception {
    synchronized (this.lock) {
      if (this.connected) {
        return true;
      }
      this.videoPipeline = videoPipeline;
      this.videoMetadata = source.getVideoMetadata();
      this.sleepTime = (long) (1000.0 / this.videoMetadata.getVideoFrameRate());

      final VernacularConfig config = this.createConfig(source);
      this.vncClient = new VernacularClient(config);
      this.vncClient.start(source.getHost(), source.getPort());

      this.connected = true;
      this.running.set(true);

      this.processingFuture = CompletableFuture.runAsync(this::processFrames, this.frameProcessorExecutor);

      return true;
    }
  }

  private VernacularConfig createConfig(final VNCSource source) {
    final VernacularConfig config = new VernacularConfig();
    config.setColorDepth(ColorDepth.BPP_16_TRUE);
    config.setShared(true);

    final String passwd = source.getPassword();
    if (passwd != null && !passwd.isEmpty()) {
      config.setPasswordSupplier(() -> passwd);
    }

    config.setTargetFramesPerSecond((int) this.videoMetadata.getVideoFrameRate());
    config.setScreenUpdateListener(image -> {
      synchronized (this.lock) {
        if (this.running.get()) {
          try {
            this.frameBuffer.offer((BufferedImage) image, 100, TimeUnit.MILLISECONDS);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }
    });
    return config;
  }

  private void processFrames() {
    try {
      final int bufferingThreshold = this.bufferCapacity / 2;
      if (this.running.get()) {
        while (this.frameBuffer.size() < bufferingThreshold && this.running.get()) {
          Thread.sleep(50);
        }
      }
      long lastProcessTime = System.currentTimeMillis();
      final long frameInterval = this.sleepTime;
      while (this.running.get() || !this.frameBuffer.isEmpty()) {
        final BufferedImage frame = this.frameBuffer.poll(frameInterval, TimeUnit.MILLISECONDS);
        if (frame == null) {
          continue;
        }
        final long currentTime = System.currentTimeMillis();
        final long processingDelay = currentTime - lastProcessTime;
        final boolean shouldSkip = processingDelay > frameInterval * 2;
        if (shouldSkip && this.frameBuffer.size() > 5) {
          lastProcessTime = currentTime;
          continue;
        }
        try {
          final ByteArrayOutputStream baos = new ByteArrayOutputStream();
          ImageIO.write(frame, "PNG", baos);
          final int width = frame.getWidth();
          final int height = frame.getHeight();
          final byte[] frameData = baos.toByteArray();
          final StaticImage image = StaticImage.bytes(frameData, width, height);
          VideoPipelineStep current = this.videoPipeline;
          while (current != null) {
            current.process(image, this.videoMetadata);
            current = current.next();
          }
        } catch (final IOException e) {
          throw new AssertionError(e);
        }

        lastProcessTime = currentTime;
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      this.running.set(false);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    synchronized (this.lock) {
      if (this.connected && this.running.get()) {
        this.running.set(false);
        return true;
      }
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    synchronized (this.lock) {
      if (this.connected && !this.running.get()) {
        this.running.set(true);

        if (this.processingFuture == null || this.processingFuture.isDone()) {
          this.processingFuture = CompletableFuture.runAsync(this::processFrames, this.frameProcessorExecutor);
        }

        return true;
      }
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    synchronized (this.lock) {
      this.running.set(false);

      if (this.connected) {
        if (this.processingFuture != null) {
          this.processingFuture.cancel(true);
          this.processingFuture = null;
        }

        if (this.vncClient != null) {
          this.vncClient.stop();
          this.vncClient = null;
        }

        this.connected = false;
        this.frameBuffer.clear();
      }

      ExecutorUtils.shutdownExecutorGracefully(this.frameRetrieverExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.frameProcessorExecutor);

      return true;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveMouse(final int x, final int y) {
    if (this.vncClient != null) {
      this.vncClient.moveMouse(x, y);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void type(final String text) {
    if (this.vncClient != null) {
      this.vncClient.type(text);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateMouseButton(final int type, final boolean pressed) {
    if (this.vncClient != null) {
      this.vncClient.updateMouseButton(type, pressed);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateKeyButton(final int keyCode, final boolean pressed) {
    if (this.vncClient != null) {
      this.vncClient.updateKey(keyCode, pressed);
    }
  }
}
