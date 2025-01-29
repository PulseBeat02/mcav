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
package me.brandonli.mcav.vnc;

import static java.util.Objects.requireNonNull;

import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.rendering.ColorDepth;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.VNCSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
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

  private final ExecutorService frameProcessorExecutor;
  private final AtomicBoolean running;
  private final Object lock = new Object();

  private @Nullable CompletableFuture<?> processingFuture;
  private @Nullable VernacularClient vncClient;

  private volatile BufferedImage current;
  private volatile VideoPipelineStep videoPipeline;
  private volatile VNCSource source;

  VNCPlayerImpl() {
    final int availableProcessors = Runtime.getRuntime().availableProcessors();
    this.frameProcessorExecutor = Executors.newFixedThreadPool(availableProcessors);
    this.running = new AtomicBoolean(false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final VNCSource source) {
    synchronized (this.lock) {
      if (this.running.get()) {
        return true;
      }
      this.videoPipeline = videoPipeline;
      this.source = source;

      final VernacularConfig config = this.createConfig(source);
      this.vncClient = new VernacularClient(config);
      this.vncClient.start(source.getHost(), source.getPort());

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

    final int fps = source.getTargetFrameRate();
    config.setTargetFramesPerSecond(fps);
    config.setScreenUpdateListener(image -> {
      if (this.running.get()) {
        if (image == null) {
          return;
        }
        this.current = (BufferedImage) image;
      }
    });
    return config;
  }

  private void processFrames() {
    final int width = this.source.getScreenWidth();
    final int height = this.source.getScreenHeight();
    final int fps = this.source.getTargetFrameRate();
    final VideoMetadata videoMetadata = VideoMetadata.of(width, height, (float) fps);
    while (this.running.get()) {
      try {
        if (this.current == null) {
          continue;
        }
        final StaticImage image = StaticImage.image(this.current);
        image.resize(width, height);
        VideoPipelineStep current = this.videoPipeline;
        while (current != null) {
          current.process(image, videoMetadata);
          current = current.next();
        }
        image.release();
      } catch (final IOException e) {
        throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    synchronized (this.lock) {
      if (this.running.get()) {
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
      if (!this.running.get()) {
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
      if (this.processingFuture != null) {
        this.processingFuture.cancel(true);
        this.processingFuture = null;
      }
      if (this.vncClient != null) {
        this.vncClient.stop();
        this.vncClient = null;
      }
      ExecutorUtils.shutdownExecutorGracefully(this.frameProcessorExecutor);
      return true;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveMouse(final int x, final int y) {
    final VernacularClient client = this.vncClient;
    if (client != null) {
      final int[] translated = this.translateCoordinates(x, y);
      client.moveMouse(translated[0], translated[1]);
    }
  }

  private int[] translateCoordinates(final int x, final int y) {
    if (this.current == null) {
      throw new PlayerException("VNC source not started!");
    }
    final int sourceWidth = this.source.getScreenWidth();
    final int sourceHeight = this.source.getScreenHeight();
    final int targetWidth = this.current.getWidth();
    final int targetHeight = this.current.getHeight();
    final double widthRatio = (double) targetWidth / sourceWidth;
    final double heightRatio = (double) targetHeight / sourceHeight;
    final int newX = (int) (x * widthRatio);
    final int newY = (int) (y * heightRatio);
    final int clampedX = Math.clamp(newX, 0, targetWidth - 1);
    final int clampedY = Math.clamp(newY, 0, targetHeight - 1);
    return new int[] { clampedX, clampedY };
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendKeyEvent(final String text) {
    if (this.vncClient != null) {
      this.vncClient.type(text);
    }
  }

  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    if (this.vncClient != null) {
      final VernacularClient client = requireNonNull(this.vncClient);
      this.moveMouse(x, y);
      switch (type) {
        case LEFT:
          client.click(1);
          break;
        case RIGHT:
          client.click(2);
          break;
        case DOUBLE:
          client.click(1);
          client.click(1);
          break;
        case HOLD:
          client.updateMouseButton(1, true);
          break;
        case RELEASE:
          client.updateMouseButton(1, false);
          break;
      }
    }
  }
}
