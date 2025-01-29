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
package me.brandonli.mcav.sandbox.command;

import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.stream.Stream;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.resourcepack.SimpleResourcePack;
import me.brandonli.mcav.sandbox.MCAV;
import me.brandonli.mcav.sandbox.locale.AudienceProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.resourcepack.SoundExtractorUtils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.suggestion.Suggestions;

public final class VideoCommand implements AnnotationCommandFeature {

  private MCAV plugin;
  private BukkitAudiences audiences;

  @Override
  public void registerFeature(final MCAV plugin, final AnnotationParser<CommandSender> parser) {
    final AudienceProvider provider = plugin.getAudience();
    this.plugin = plugin;
    this.audiences = provider.retrieve();
  }

  @Command("mcav maps <videoResolution> <blockDimensions> <mapId> <mrl>")
  @Permission("mcav.browser")
  @CommandDescription("mcav.command.browser.info")
  public void playMapsVideo(
    final Player player,
    @Argument(suggestions = "resolutions") @Quoted final String videoResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "id") @Range(min = "0", max = "4294967295") final int mapId,
    @Quoted final String mrl
  ) {
    final Audience audience = this.audiences.sender(player);
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(videoResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      audience.sendMessage(Message.DIMENSION_ERROR.build());
      return;
    }

    final boolean isStream = SourceUtils.isDynamicStream(mrl);
    if (!isStream) {
      try {
        final Source source = SourceUtils.isPath(mrl) ? FileSource.path(Path.of(mrl)) : UriSource.uri(URI.create(mrl));
        final Path ogg = SoundExtractorUtils.extractOggAudio(source);
        final SimpleResourcePack pack = SimpleResourcePack.pack();
        //        pack.sound();
      } catch (final IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Suggestions("id")
  public Stream<Integer> suggestId(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of(0, 5, 10, 100, 1000, 10000, 100000);
  }

  @Suggestions("dimensions")
  public Stream<String> suggestDimensions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("4x4", "5x5", "16x9", "32x18");
  }

  @Suggestions("resolutions")
  public Stream<String> suggestResolutions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("512x512", "640x640", "1280x720", "1920x1080");
  }
}
