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
package me.brandonli.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import me.brandonli.filter.ThreadSafeVideoFilter;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

public final class ClientTestCommand {

  public static void register(final CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(literal("test").executes(ClientTestCommand::execute));
  }

  private static int execute(final CommandContext<FabricClientCommandSource> context) {
    final FabricClientCommandSource source = context.getSource();

    try {
      // This runs on the client thread
      final VideoPlayerMultiplexer player = VideoPlayer.vlc();
      final ThreadSafeVideoFilter filter = new ThreadSafeVideoFilter();
      filter.start();

      final VideoAttachableCallback callback = player.getVideoAttachableCallback();
      callback.attach(VideoPipelineStep.of(filter));

      final DimensionAttachableCallback dimension = player.getDimensionAttachableCallback();
      dimension.attach(new Dimension(1920, 1080));

      final AudioAttachableCallback audio = player.getAudioAttachableCallback();
      audio.attach(AudioPipelineStep.of(new DirectAudioOutput()));

      player.start(FileSource.path(Path.of("C:\\rickroll.mp4")));

      final int textureId = filter.getTextureId();
      source.sendFeedback(Text.literal("Started streaming video to all TV blocks with texture ID: " + textureId));

      CompletableFuture.runAsync(() -> {
        try {
          Thread.sleep(10000L); // Play for 10 seconds
          player.release();
          filter.release();
          source.sendFeedback(Text.literal("Video playback stopped."));
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    } catch (final Exception e) {
      source.sendError(Text.literal("Error starting video stream: " + e.getMessage()));
      return 0;
    }

    return 1;
  }

  private ClientTestCommand() {}
}
