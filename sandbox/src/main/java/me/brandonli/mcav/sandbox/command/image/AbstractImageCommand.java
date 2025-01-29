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

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.AnnotationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractImageCommand implements AnnotationCommandFeature {

  protected MCAVSandbox plugin;
  protected ImageManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.plugin = plugin;
    this.manager = plugin.getImageManager();
  }

  public void displayImage(
    final ImageConfigurationProvider configProvider,
    final CommandSender player,
    final String videoResolution,
    final String image
  ) {
    final Pair<Integer, Integer> resolution = this.sanitizeResolution(videoResolution);
    if (resolution == null) {
      player.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }
    player.sendMessage(Message.LOAD_IMAGE_START.build());

    final ExecutorService service = this.manager.getService();
    final Runnable command = () -> this.synchronizeImage(image, player, resolution, configProvider);
    CompletableFuture.runAsync(command, service)
      .thenRun(TaskUtils.handleAsyncTask(this.plugin, () -> player.sendMessage(Message.LOAD_IMAGE.build())))
      .exceptionally(this::handleException);
  }

  private @Nullable Void handleException(final Throwable throwable) {
    final Logger logger = LoggerFactory.getLogger("MCAV Image");
    logger.error("An exception occurred while rendering an image", throwable);
    return null;
  }

  private @Nullable Pair<Integer, Integer> sanitizeResolution(final String videoResolution) {
    try {
      return ArgumentUtils.parseDimensions(videoResolution);
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  private synchronized void synchronizeImage(
    final String image,
    final Audience audience,
    final Pair<Integer, Integer> resolution,
    final ImageConfigurationProvider configProvider
  ) {
    @Nullable final Source source = this.retrieveSource(image);
    if (source == null) {
      audience.sendMessage(Message.UNSUPPORTED_MRL.build());
      return;
    }
    this.manager.releaseImage(false);
    try {
      this.processImage(resolution, source, configProvider);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  private void processImage(final Pair<Integer, Integer> resolution, final Source source, final ImageConfigurationProvider configProvider)
    throws IOException {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTaskLater(
      this.plugin,
      () -> {
        final DisplayableImage displayableImage = this.createImage(resolution, configProvider);
        this.manager.setImage(displayableImage);
        final ImageBuffer image =
          switch (source) {
            case final FileSource fileSource -> ImageBuffer.path(fileSource);
            case final UriSource uriSource -> ImageBuffer.uri(uriSource);
            default -> throw new IllegalArgumentException("Unsupported source type");
          };
        this.manager.setCurrentImage(image);
        scheduler.runTask(this.plugin, () -> displayableImage.displayImage(image));
      },
      5L
    );
  }

  public abstract DisplayableImage createImage(Pair<Integer, Integer> resolution, ImageConfigurationProvider configProvider);

  private @Nullable Source retrieveSource(final String mrl) {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> optional = helper.detectSource(mrl);
    if (optional.isEmpty()) {
      return null;
    }

    final Source source = optional.get();
    if (source instanceof UriSource && SourceUtils.isImageGif(source)) {
      return null;
    }

    return source;
  }

  public interface ImageConfigurationProvider {
    Object buildConfiguration(Pair<Integer, Integer> resolution);
  }
}
