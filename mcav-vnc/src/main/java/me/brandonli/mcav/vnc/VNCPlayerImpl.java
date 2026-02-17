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
package me.brandonli.mcav.vnc;

import static java.util.Objects.requireNonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.rendering.ColorDepth;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.CollectionUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.KeyFor;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A VNCPlayer implementation that handles VNC connections and video frame processing.
 */
public class VNCPlayerImpl implements VNCPlayer {

  private static final Consumer<VernacularClient>[] MOUSE_ACTION_CONSUMERS = CollectionUtils.array(
    client -> client.click(1),
    client -> client.click(2),
    client -> {
      client.click(1);
      client.click(1);
    },
    client -> client.updateMouseButton(1, true),
    client -> client.updateMouseButton(1, false)
  );

  private static final Map<String, Integer> KEY_SYMBOLS;

  static {
    final Gson gson = GsonProvider.getSimple();
    try (final Reader reader = IOUtils.getResourceAsStreamReader("keysyms.json")) {
      final TypeToken<Map<String, String>> token = new TypeToken<>() {};
      final Type type = token.getType();
      final Map<String, String> map = requireNonNull(gson.fromJson(reader, type));
      final Set<Map.Entry<@KeyFor("map") String, String>> entries = map.entrySet();
      final Map<String, Integer> symbols = new HashMap<>();
      for (final Map.Entry<String, String> entry : entries) {
        final String key = entry.getKey();
        final String value = entry.getValue();
        final int decode = Integer.decode(value);
        symbols.put(key, decode);
      }
      KEY_SYMBOLS = Map.copyOf(symbols);
    } catch (final IOException e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private final VideoAttachableCallback videoCallback;
  private final ExecutorService frameProcessorExecutor;
  private final AtomicReference<@Nullable BufferedImage> current;
  private final AtomicBoolean running;
  private final Lock lock;

  @Nullable private volatile VernacularClient vncClient;

  @Nullable private volatile VNCSource source;

  private volatile BiConsumer<String, Throwable> exceptionHandler;

  VNCPlayerImpl() {
    this.exceptionHandler = ExceptionHandler.createDefault().getExceptionHandler();
    this.videoCallback = VideoAttachableCallback.create();
    this.frameProcessorExecutor = Executors.newSingleThreadExecutor();
    this.current = new AtomicReference<>(null);
    this.running = new AtomicBoolean(false);
    this.lock = new ReentrantLock();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VNCSource source) {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.running.get()) {
        return true;
      }

      final VernacularConfig config = this.createConfig(source);
      final String host = source.getHost();
      final int port = source.getPort();
      this.source = source;
      this.running.set(true);

      final VernacularClient client = new VernacularClient(config);
      client.start(host, port);
      this.vncClient = client;

      return true;
    });
  }

  private VernacularConfig createConfig(final VNCSource source) {
    final int fps = source.getTargetFrameRate();
    final VernacularConfig config = new VernacularConfig();
    config.setColorDepth(ColorDepth.BPP_16_TRUE);
    config.setShared(true);
    config.setTargetFramesPerSecond(fps);

    final int width = source.getScreenWidth();
    final int height = source.getScreenHeight();
    final VideoPipelineStep videoPipeline = this.videoCallback.retrieve();
    final OriginalVideoMetadata videoMetadata = OriginalVideoMetadata.of(width, height, fps);
    config.setScreenUpdateListener(image -> this.processImageAsync(image, videoMetadata, videoPipeline));

    final String username = source.getUsername();
    if (username != null && !username.isEmpty()) {
      config.setUsernameSupplier(() -> username);
    }

    final String passwd = source.getPassword();
    if (passwd != null && !passwd.isEmpty()) {
      config.setPasswordSupplier(() -> passwd);
    }

    return config;
  }

  private void processImageAsync(final Image image, final OriginalVideoMetadata videoMetadata, final VideoPipelineStep pipelineStep) {
    this.frameProcessorExecutor.submit(() -> this.processImage(image, videoMetadata, pipelineStep));
  }

  private void processImage(final Image image, final OriginalVideoMetadata videoMetadata, final VideoPipelineStep pipelineStep) {
    if (!this.running.get()) {
      return;
    }

    if (!(image instanceof final BufferedImage bufferedImage)) {
      return;
    }
    this.current.set(bufferedImage);

    final int width = videoMetadata.getVideoWidth();
    final int height = videoMetadata.getVideoHeight();
    try {
      final ImageBuffer staticImage = ImageBuffer.image(bufferedImage);
      final ResizeFilter resizeFilter = new ResizeFilter(width, height);
      resizeFilter.applyFilter(staticImage, videoMetadata);
      VideoPipelineStep current = pipelineStep;
      while (current != null) {
        current.process(staticImage, videoMetadata);
        current = current.next();
      }
      staticImage.release();
    } catch (final Throwable e) {
      final String raw = e.getMessage();
      final Class<?> clazz = e.getClass();
      final String msg = raw != null ? raw : clazz.getName();
      this.exceptionHandler.accept(msg, e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.running.get()) {
        this.running.set(false);
        return true;
      }
      return false;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.running.get()) {
        this.running.set(true);
        return true;
      }
      return false;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      if (this.vncClient != null) {
        final VernacularClient client = requireNonNull(this.vncClient);
        client.stop();
        this.vncClient = null;
      }
      ExecutorUtils.shutdownExecutorGracefully(this.frameProcessorExecutor);

      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveMouse(final int x, final int y) {
    LockUtils.executeWithLock(this.lock, () -> {
      final BufferedImage currentImage = this.current.get();
      final VNCSource source = this.source;
      if (currentImage == null || source == null || !this.running.get()) {
        return;
      }

      final VernacularClient client = this.vncClient;
      if (client != null) {
        final int[] translated = this.translateCoordinates(x, y);
        client.moveMouse(translated[0], translated[1]);
      }
    });
  }

  private int[] translateCoordinates(final int x, final int y) {
    final BufferedImage currentImage = requireNonNull(this.current.get());
    final VNCSource source = requireNonNull(this.source);
    final int sourceWidth = source.getScreenWidth();
    final int sourceHeight = source.getScreenHeight();
    final int targetWidth = currentImage.getWidth();
    final int targetHeight = currentImage.getHeight();
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
    LockUtils.executeWithLock(this.lock, () -> {
      if (this.running.get() && this.vncClient != null) {
        final VernacularClient client = requireNonNull(this.vncClient);
        if (KEY_SYMBOLS.containsKey(text)) {
          final int ks = KEY_SYMBOLS.get(text);
          client.updateKey(ks, true);
          client.updateKey(ks, false);
          return;
        }
        client.type(text);
      }
    });
  }

  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    LockUtils.executeWithLock(this.lock, () -> {
      if (!this.running.get() || this.vncClient == null) {
        return;
      }

      final VernacularClient client = requireNonNull(this.vncClient);
      final int value = type.getId();
      final Consumer<VernacularClient> action = MOUSE_ACTION_CONSUMERS[value];
      this.moveMouse(x, y);
      action.accept(client);
    });
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }
}
