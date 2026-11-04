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
package me.brandonli.mcav.plugin.audio;

import com.google.common.base.Preconditions;
import java.util.Optional;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the commands that play sound tell their sender and viewers about the audio output they chose: whether it can
 * play now, and the link to the Discord channel or the web page the sound plays in.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class AudioOutputs {

  private static final String PROVIDER_NULL_MESSAGE = "Provider must not be null!";
  private static final String AUDIO_TYPE_NULL_MESSAGE = "Audio type must not be null!";

  private AudioOutputs() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Checks whether an audio output can play now. The Discord bot and the web page start in the background when the
   * plugin is enabled, so they are only usable once they are ready.
   *
   * @param provider  the provider of the audio outputs
   * @param audioType the chosen output
   * @return the message that explains why the output cannot play, or empty if it can
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public static Optional<Component> findProblem(final AudioProvider provider, final AudioArgument audioType) {
    Preconditions.checkNotNull(provider, PROVIDER_NULL_MESSAGE);
    Preconditions.checkNotNull(audioType, AUDIO_TYPE_NULL_MESSAGE);

    return switch (audioType) {
      case DISCORD_BOT -> {
        final boolean enabled = provider.isDiscordBotEnabled();
        final boolean ready = provider.isDiscordBotReady();
        yield describe(enabled, ready);
      }
      case HTTP_SERVER -> {
        final boolean enabled = provider.isHttpEnabled();
        final boolean ready = provider.isHttpReady();
        yield describe(enabled, ready);
      }
      case NONE, SIMPLE_VOICE_CHAT -> Optional.empty();
    };
  }

  private static Optional<Component> describe(final boolean enabled, final boolean ready) {
    if (!enabled) {
      final Component message = Message.UNSUPPORTED_AUDIO.build();
      return Optional.of(message);
    }

    if (!ready) {
      final Component message = Message.AUDIO_NOT_READY.build();
      return Optional.of(message);
    }

    return Optional.empty();
  }

  /**
   * Sends the viewers the link to where the sound plays, for the outputs that have one.
   *
   * @param provider  the provider of the audio outputs
   * @param audioType the chosen output
   * @param viewers   the players who see the picture
   * @since 1.0.0
   */
  public static void sendLink(final AudioProvider provider, final AudioArgument audioType, final Player[] viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    final Optional<Component> foundLink = createLink(provider, audioType);
    final @Nullable Component link = foundLink.orElse(null);
    if (link == null) {
      return;
    }

    for (final Player viewer : viewers) {
      viewer.sendMessage(link);
    }
  }

  /**
   * Creates the link to where the sound of an output plays.
   *
   * @param provider  the provider of the audio outputs
   * @param audioType the chosen output
   * @return the link, or empty for outputs without one
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public static Optional<Component> createLink(final AudioProvider provider, final AudioArgument audioType) {
    Preconditions.checkNotNull(provider, PROVIDER_NULL_MESSAGE);
    Preconditions.checkNotNull(audioType, AUDIO_TYPE_NULL_MESSAGE);

    return switch (audioType) {
      case DISCORD_BOT -> {
        final String url = provider.constructVoiceChannelUrl();
        final Component link = Message.AUDIO_DISCORD.build(url);
        yield Optional.of(link);
      }
      case HTTP_SERVER -> {
        final String url = provider.constructHttpUrl();
        final Component link = Message.AUDIO_HTTP.build(url);
        yield Optional.of(link);
      }
      case NONE, SIMPLE_VOICE_CHAT -> Optional.empty();
    };
  }
}
