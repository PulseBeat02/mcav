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
package me.brandonli.mcav.plugin.command.image;

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
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.SourceDetectionHelper;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.command.video.AbstractVideoCommand;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.FilterChain;
import me.brandonli.mcav.plugin.utils.MediaKind;
import me.brandonli.mcav.plugin.utils.TaskUtils;
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
 *
 * <p>This class is not thread-safe: use it on the server main thread; images load on the image manager's worker.
 *
 * @since 1.0.0
 */
public abstract class AbstractImageCommand implements AnnotationCommandFeature {

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractImageCommand.class);

  private static final char QUOTE = '"';
  private static final int QUOTE_COUNT = 2;

  /**
   * The plugin.
   *
   * @since 1.0.0
   */
  protected final MCAVSandbox plugin;

  private final ImageManager manager;

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  protected AbstractImageCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.plugin = plugin;
    this.manager = plugin.getImageManager();
  }

  /**
   * Loads an image, scales it to the resolution, and shows it, replacing the image shown before.
   *
   * @param configurationProvider creates the display configuration for the parsed resolution
   * @param sender                who ran the command
   * @param imageResolution       the resolution the image is scaled to, such as {@code 640x640}
   * @param mrl                   the path or URL of the image, optionally enclosed in one pair of double quotes
   * @param filters               the {@code --filters} option, see {@link FilterChain}, or {@code null} for none
   * @throws NullPointerException if an argument other than {@code filters} is null
   * @since 1.0.0
   */
  public void displayImage(
    final ImageConfigurationProvider configurationProvider,
    final CommandSender sender,
    final String imageResolution,
    final String mrl,
    final @Nullable String filters
  ) {
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null!");
    Preconditions.checkNotNull(sender, "Sender must not be null!");
    Preconditions.checkNotNull(imageResolution, "Image resolution must not be null!");
    Preconditions.checkNotNull(mrl, "MRL must not be null!");

    final Pair<Integer, Integer> resolution = this.parseResolution(imageResolution);
    if (resolution == null) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return;
    }

    final Optional<FilterChain> parsedChain = AbstractVideoCommand.parseFilters(sender, this.plugin, filters, MediaKind.IMAGE);
    final @Nullable FilterChain chain = parsedChain.orElse(null);
    if (chain == null) {
      return;
    }

    final String sourceMrl = this.unwrapMrl(mrl);
    final Supplier<ImageBuffer> loader = this.createLoader(sourceMrl);
    if (loader == null) {
      final Component message = Message.UNSUPPORTED_MRL.build();
      sender.sendMessage(message);
      return;
    }

    final ImageRequest request = new ImageRequest(sender, sourceMrl, resolution, configurationProvider);
    final Supplier<ImageBuffer> scaledLoader = () -> this.scaleAndFilter(loader.get(), resolution, chain);
    this.startLoading(scaledLoader, request);
  }

  // resized first, which bounds the filters' work
  private ImageBuffer scaleAndFilter(final ImageBuffer image, final Pair<Integer, Integer> resolution, final FilterChain chain) {
    try {
      final int width = resolution.getFirst();
      final int height = resolution.getSecond();
      final ResizeFilter resize = new ResizeFilter(width, height);
      resize.applyFilter(image);
      chain.apply(image);

      return image;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, image::release);
      throw exception;
    }
  }

  private void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final boolean sameFailure = FAILURE_IDENTITY.equivalent(failure, exception);
      if (!sameFailure) {
        failure.addSuppressed(exception);
      }
    }
  }

  private String unwrapMrl(final String mrl) {
    final int length = mrl.length();
    if (length >= QUOTE_COUNT && mrl.charAt(0) == QUOTE && mrl.charAt(length - 1) == QUOTE) {
      return mrl.substring(1, length - 1);
    }

    return mrl;
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
      LOGGER.error("The image worker refused to load an image.", exception);
      final Component message = Message.UNSUPPORTED_MRL.build();
      sender.sendMessage(message);
      return;
    }

    TaskUtils.whenComplete(loading, (image, error) -> this.onImageLoaded(request, generation, image, error));
  }

  // a disabled plugin's main thread takes no tasks, so the image is released at once
  private void onImageLoaded(
    final ImageRequest request,
    final long generation,
    final @Nullable ImageBuffer image,
    final @Nullable Throwable error
  ) {
    final CommandSender sender = request.getSender();
    if (error != null) {
      final String mrl = request.getMrl();
      LOGGER.error("Image {} could not be loaded.", mrl, error);
      final Component message = Message.UNSUPPORTED_MRL.build();
      TaskUtils.runOnMainThread(this.plugin, () -> sender.sendMessage(message));
      return;
    }

    final ImageBuffer loaded = Objects.requireNonNull(image);
    final boolean retained = this.manager.retainLoaded(generation, loaded);
    if (!retained) {
      loaded.release();
      return;
    }

    final Pair<Integer, Integer> resolution = request.getResolution();
    final ImageConfigurationProvider configurationProvider = request.getConfigurationProvider();
    try {
      final boolean scheduled = TaskUtils.runOnMainThread(this.plugin, () ->
        this.showImage(generation, resolution, configurationProvider, sender)
      );
      if (!scheduled) {
        this.manager.discardLoaded(generation);
      }
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, () -> this.manager.discardLoaded(generation));
      throw exception;
    }
  }

  // main thread only, like shutdown, so showing an image cannot interleave with shutdown
  private void showImage(
    final long generation,
    final Pair<Integer, Integer> resolution,
    final ImageConfigurationProvider configurationProvider,
    final CommandSender sender
  ) {
    final @Nullable ImageBuffer image = this.manager.takeLoaded(generation);
    if (image == null) {
      return;
    }

    boolean adopted = false;
    try {
      this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
      final DisplayableImage display = this.createImage(resolution, configurationProvider);
      this.manager.setImage(display);
      this.manager.setCurrentImage(image);
      adopted = true;
      display.displayImage(image);
      final Component message = Message.LOAD_IMAGE.build();
      sender.sendMessage(message);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      if (adopted) {
        this.cleanupAfterFailure(exception, () -> this.manager.releaseImage(ReleaseThread.CALLING_THREAD));
      } else {
        this.cleanupAfterFailure(exception, image::release);
      }
      throw exception;
    }
  }

  private @Nullable Pair<Integer, Integer> parseResolution(final String imageResolution) {
    try {
      return ArgumentUtils.parseDimensions(imageResolution);
    } catch (final IllegalArgumentException exception) {
      return null;
    }
  }

  private @Nullable Supplier<ImageBuffer> createLoader(final String mrl) {
    if (mrl.isBlank()) {
      return null;
    }

    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> detected = helper.detectSource(mrl);
    final @Nullable Source source = detected.orElse(null);
    if (source == null) {
      return null;
    }

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
   * @param resolution            the parsed resolution
   * @param configurationProvider the provider passed to {@link #displayImage}
   * @return the display
   * @since 1.0.0
   */
  public abstract DisplayableImage createImage(
    final Pair<Integer, Integer> resolution,
    final ImageConfigurationProvider configurationProvider
  );

  private static final class ImageRequest {

    private final CommandSender sender;
    private final String mrl;
    private final Pair<Integer, Integer> resolution;
    private final ImageConfigurationProvider configurationProvider;

    ImageRequest(
      final CommandSender sender,
      final String mrl,
      final Pair<Integer, Integer> resolution,
      final ImageConfigurationProvider configurationProvider
    ) {
      this.sender = sender;
      this.mrl = mrl;
      this.resolution = resolution;
      this.configurationProvider = configurationProvider;
    }

    private CommandSender getSender() {
      return this.sender;
    }

    private String getMrl() {
      return this.mrl;
    }

    private Pair<Integer, Integer> getResolution() {
      return this.resolution;
    }

    private ImageConfigurationProvider getConfigurationProvider() {
      return this.configurationProvider;
    }
  }

  /**
   * Creates the display configuration of an image command for a resolution.
   *
   * <p>Implementations in this library are immutable and thread-safe.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  public interface ImageConfigurationProvider {
    /**
     * Creates the configuration.
     *
     * @param resolution the parsed resolution
     * @return the configuration object the subclass expects
     * @since 1.0.0
     */
    Object buildConfiguration(final Pair<Integer, Integer> resolution);
  }
}
