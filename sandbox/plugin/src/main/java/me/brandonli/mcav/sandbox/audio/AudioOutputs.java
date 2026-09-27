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
package me.brandonli.mcav.sandbox.audio;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the commands that play sound tell their sender and viewers about the audio output they chose: whether it can
 * play now, and the link to the Discord channel or the web page the sound plays in.
 */
public final class AudioOutputs {

  private AudioOutputs() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks whether an audio output can play now. The Discord bot and the web page start in the background when the
   * plugin is enabled, so they are only usable once they are ready.
   *
   * @param provider  the provider of the audio outputs
   * @param audioType the chosen output
   * @return the message that explains why the output cannot play, or {@code null} if it can
   */
  public static @Nullable Component findProblem(final AudioProvider provider, final AudioArgument audioType) {
    Preconditions.checkNotNull(provider, "Provider must not be null");
    Preconditions.checkNotNull(audioType, "Audio type must not be null");
    return switch (audioType) {
      case DISCORD_BOT -> describe(provider.isDiscordBotEnabled(), provider.isDiscordBotReady());
      case HTTP_SERVER -> describe(provider.isHttpEnabled(), provider.isHttpReady());
      case NONE, SIMPLE_VOICE_CHAT -> null;
    };
  }

  private static @Nullable Component describe(final boolean enabled, final boolean ready) {
    if (!enabled) {
      return Message.UNSUPPORTED_AUDIO.build();
    }
    if (!ready) {
      return Message.AUDIO_NOT_READY.build();
    }
    return null;
  }

  /**
   * Sends the viewers the link to where the sound plays, for the outputs that have one.
   *
   * @param provider  the provider of the audio outputs
   * @param audioType the chosen output
   * @param viewers   the players who see the picture
   */
  public static void sendLink(final AudioProvider provider, final AudioArgument audioType, final Player[] viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null");
    final Component link = createLink(provider, audioType);
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
   * @return the link, or {@code null} for outputs without one
   */
  public static @Nullable Component createLink(final AudioProvider provider, final AudioArgument audioType) {
    Preconditions.checkNotNull(provider, "Provider must not be null");
    Preconditions.checkNotNull(audioType, "Audio type must not be null");
    return switch (audioType) {
      case DISCORD_BOT -> Message.AUDIO_DISCORD.build(provider.constructVoiceChannelUrl());
      case HTTP_SERVER -> Message.AUDIO_HTTP.build(provider.constructHttpUrl());
      case NONE, SIMPLE_VOICE_CHAT -> null;
    };
  }
}
