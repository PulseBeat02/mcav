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
package me.brandonli.mcav.sandbox.command.image;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.SourceDetectionHelper;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The common flow of the image commands: parse the resolution, load the image on the worker thread, and show it
 * on the main thread with the display type of the subclass.
 */
public abstract class AbstractImageCommand implements AnnotationCommandFeature {

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();
  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractImageCommand.class);

  /**
   * The plugin.
   */
  protected final MCAVSandbox plugin;

  /**
   * The manager of the displayed image.
   */
  protected final ImageManager manager;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  protected AbstractImageCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.manager = plugin.getImageManager();
  }

  /**
   * Loads and shows an image, replacing the image shown before.
   *
   * @param configProvider   creates the display configuration for the parsed resolution
   * @param sender           who ran the command
   * @param imageResolution  the resolution argument, such as {@code 640x640}
   * @param mrl              the path or URL of the image, optionally enclosed in one pair of double quotes
   */
  public void displayImage(
    final ImageConfigurationProvider configProvider,
    final CommandSender sender,
    final String imageResolution,
    final String mrl
  ) {
    Preconditions.checkNotNull(configProvider, "Configuration provider must not be null");
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(imageResolution, "Image resolution must not be null");
    Preconditions.checkNotNull(mrl, "MRL must not be null");
    final Pair<Integer, Integer> resolution = parseResolution(imageResolution);
    if (resolution == null) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return;
    }

    final Supplier<ImageBuffer> loader = createLoader(mrl);
    if (loader == null) {
      final Component message = Message.UNSUPPORTED_MRL.build();
      sender.sendMessage(message);
      return;
    }

    final ImageRequest request = new ImageRequest(sender, mrl, resolution, configProvider);
    this.startLoading(loader, request);
  }

  private void startLoading(final Supplier<ImageBuffer> loader, final ImageRequest request) {
    final CommandSender sender = request.getSender();
    final Component loadingMessage = Message.LOAD_IMAGE_START.build();
    sender.sendMessage(loadingMessage);

    final ExecutorService service = this.manager.getService();
    final long generation;
    final CompletableFuture<ImageBuffer> loading;
    try {
      generation = this.manager.beginLoad();
      loading = CompletableFuture.supplyAsync(loader, service);
    } catch (final RejectedExecutionException exception) {
      LOGGER.error("The image worker refused to load an image", exception);
      final Component message = Message.UNSUPPORTED_MRL.build();
      sender.sendMessage(message);
      return;
    }
    TaskUtils.whenComplete(loading, (image, error) -> this.onImageLoaded(request, generation, image, error));
  }

  /**
   * Hands the loaded image over to the main thread. When the plugin is disabled, the main thread accepts no more
   * tasks, so the image is released at once instead of leaking its native memory.
   */
  private void onImageLoaded(
    final ImageRequest request,
    final long generation,
    final @Nullable ImageBuffer image,
    final @Nullable Throwable error
  ) {
    final CommandSender sender = request.getSender();
    if (error != null) {
      final String mrl = request.getMrl();
      LOGGER.error("Failed to load the image {}", mrl, error);
      final Component message = Message.UNSUPPORTED_MRL.build();
      TaskUtils.runOnMainThread(this.plugin, () -> sender.sendMessage(message));
      return;
    }
    // without an error the loader always returns an image
    final ImageBuffer loaded = Objects.requireNonNull(image);
    final boolean retained = this.manager.retainLoaded(generation, loaded);
    if (!retained) {
      loaded.release();
      return;
    }
    final Pair<Integer, Integer> resolution = request.getResolution();
    final ImageConfigurationProvider configProvider = request.getConfigProvider();
    try {
      final boolean scheduled = TaskUtils.runOnMainThread(this.plugin, () -> this.showImage(generation, resolution, configProvider, sender)
      );
      if (!scheduled) {
        this.manager.discardLoaded(generation);
      }
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      try {
        this.manager.discardLoaded(generation);
      } catch (final RuntimeException | Error cleanupFailure) {
        ThrowableUtils.throwIfFatal(cleanupFailure);
        final boolean sameFailure = FAILURE_IDENTITY.equivalent(exception, cleanupFailure);
        if (!sameFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  /**
   * Runs only on the server main thread, as do manager shutdown and display ownership changes. Taking a pending
   * image and publishing its display cannot interleave with shutdown on that thread.
   */
  private void showImage(
    final long generation,
    final Pair<Integer, Integer> resolution,
    final ImageConfigurationProvider configProvider,
    final CommandSender sender
  ) {
    final ImageBuffer image = this.manager.takeLoaded(generation);
    if (image == null) {
      return;
    }
    boolean adopted = false;
    try {
      this.manager.releaseImage(true);
      final DisplayableImage display = this.createImage(resolution, configProvider);
      this.manager.setImage(display);
      this.manager.setCurrentImage(image);
      adopted = true;
      display.displayImage(image);
      final Component message = Message.LOAD_IMAGE.build();
      sender.sendMessage(message);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      try {
        if (adopted) {
          this.manager.releaseImage(true);
        } else {
          image.release();
        }
      } catch (final RuntimeException | Error cleanupFailure) {
        ThrowableUtils.throwIfFatal(cleanupFailure);
        final boolean sameFailure = FAILURE_IDENTITY.equivalent(exception, cleanupFailure);
        if (!sameFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  private static @Nullable Pair<Integer, Integer> parseResolution(final String imageResolution) {
    try {
      return ArgumentUtils.parseDimensions(imageResolution);
    } catch (final IllegalArgumentException exception) {
      return null;
    }
  }

  /**
   * Creates the loader of a still image from a file or a URL.
   *
   * @return the loader, or {@code null} if the media is not such an image
   */
  private static @Nullable Supplier<ImageBuffer> createLoader(final String mrl) {
    if (mrl.isBlank()) {
      return null;
    }
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> detected = helper.detectSource(mrl);
    if (detected.isEmpty()) {
      return null;
    }
    final Source source = detected.get();
    final boolean animated = SourceUtils.isImageGif(source);
    if (animated) {
      return null;
    }
    if (source instanceof final FileSource fileSource) {
      return () -> ImageBuffer.path(fileSource);
    }
    if (source instanceof final UriSource uriSource) {
      return () -> ImageBuffer.uri(uriSource);
    }
    return null;
  }

  /**
   * Creates the display for the image. Called on the main thread.
   *
   * @param resolution     the parsed resolution
   * @param configProvider the provider passed to {@link #displayImage}
   * @return the display
   */
  public abstract DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider);

  /**
   * What the sender asked to show, kept while the image loads.
   */
  private static final class ImageRequest {

    private final CommandSender sender;
    private final String mrl;
    private final Pair<Integer, Integer> resolution;
    private final ImageConfigurationProvider configProvider;

    ImageRequest(
      final CommandSender sender,
      final String mrl,
      final Pair<Integer, Integer> resolution,
      final ImageConfigurationProvider configProvider
    ) {
      this.sender = sender;
      this.mrl = mrl;
      this.resolution = resolution;
      this.configProvider = configProvider;
    }

    CommandSender getSender() {
      return this.sender;
    }

    String getMrl() {
      return this.mrl;
    }

    Pair<Integer, Integer> getResolution() {
      return this.resolution;
    }

    ImageConfigurationProvider getConfigProvider() {
      return this.configProvider;
    }
  }

  /**
   * Creates the display configuration of an image command for a resolution.
   */
  @FunctionalInterface
  public interface ImageConfigurationProvider {
    /**
     * Creates the configuration.
     *
     * @param resolution the parsed resolution
     * @return the configuration object the subclass expects
     */
    Object buildConfiguration(final Pair<Integer, Integer> resolution);
  }
}
