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
import java.util.List;
import java.util.concurrent.*;
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
 *
 * The class uses an {@link ExecutorService} for frame capturing and processing, with
 * distinct executors for these tasks. Frames received from the VNC server are processed
 * synchronously using a video processing pipeline.
 */
public class VNCPlayerImpl implements VNCPlayer {

  private final ExecutorService executor;
  private final ExecutorService processor;
  private final List<CompletableFuture<?>> pendingTasks;
  private final Object lock = new Object();

  private boolean paused;
  private boolean connected;
  private @Nullable CompletableFuture<?> future;
  private @Nullable VernacularClient vncClient;
  private @Nullable BufferedImage currentFrame;

  private VideoPipelineStep videoPipeline;
  private VideoMetadata videoMetadata;

  VNCPlayerImpl() {
    this.executor = Executors.newSingleThreadExecutor();
    this.processor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    this.pendingTasks = new CopyOnWriteArrayList<>();
    this.paused = false;
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

      final VernacularConfig config = this.createConfig(source);
      this.vncClient = new VernacularClient(config);
      this.vncClient.start(source.getHost(), source.getPort());

      this.connected = true;
      this.paused = false;
      this.future = CompletableFuture.runAsync(this::captureFrames, this.executor);

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
        this.currentFrame = (BufferedImage) image;
      }
    });

    return config;
  }

  private void captureFrames() {
    try {
      while (!Thread.currentThread().isInterrupted() && this.connected) {
        if (!this.paused && this.vncClient != null) {
          if (this.currentFrame == null) {
            Thread.sleep(5000L);
            continue;
          }
          final ByteArrayOutputStream baos = new ByteArrayOutputStream();
          ImageIO.write(this.currentFrame, "PNG", baos);
          final byte[] frameData = baos.toByteArray();
          final CompletableFuture<?> task = CompletableFuture.runAsync(
            () -> {
              final StaticImage image = StaticImage.bytes(frameData);
              VideoPipelineStep current = this.videoPipeline;
              while (current != null) {
                current.process(image, this.videoMetadata);
                current = current.next();
              }
            },
            this.processor
          );
          this.pendingTasks.add(task);
          task.whenComplete((result, ex) -> this.pendingTasks.remove(task));
        }
      }
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    synchronized (this.lock) {
      if (this.connected && !this.paused) {
        this.paused = true;
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
      if (this.connected && this.paused) {
        this.paused = false;
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
      if (this.connected) {
        if (this.future != null) {
          this.future.cancel(true);
          this.future = null;
        }
        if (this.vncClient != null) {
          this.vncClient.stop();
          this.vncClient = null;
        }
        this.currentFrame = null;
        this.connected = false;
      }

      try {
        CompletableFuture.allOf(this.pendingTasks.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
      } catch (final TimeoutException | ExecutionException e) {
        throw new AssertionError(e);
      }

      ExecutorUtils.shutdownExecutorGracefully(this.executor);
      ExecutorUtils.shutdownExecutorGracefully(this.processor);

      return true;
    }
  }
}
