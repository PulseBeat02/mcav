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
package me.brandonli.mcav.jda;

import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/**
 * Represents a specialized filter interface for handling Discord's audio data.
 * This interface combines the behavior of {@code AudioFilter}, {@code AudioSendHandler},
 * and {@code AudioReceiveHandler}, enabling it to process, send, and receive audio data
 * effectively within the Discord voice pipeline.
 * <p>
 * The {@code DiscordFilter} is designed to process incoming and outgoing audio streams,
 * apply transformations or filters, and provide audio data in a format that complies
 * with Discord's 20ms audio frame requirement.
 * <p>
 * Key Responsibilities:
 * - Filtering and transforming audio data using methods defined in {@code AudioFilter}.
 * - Sending audio data chunks in 20ms intervals via {@code AudioSendHandler}.
 * - Receiving and integrating incoming audio streams via {@code AudioReceiveHandler}.
 * <p>
 * This interface provides a factory method {@code filter()} for instantiating a default
 * implementation.
 */
public interface DiscordPlayer extends AudioFilter, AudioSendHandler, AudioReceiveHandler {
  /**
   * Creates and returns a new instance of the default implementation of {@code DiscordFilter}.
   * The returned instance implements the {@code DiscordFilter} interface, providing functionality
   * to process, filter, send, and receive Discord audio data in compliance with Discord's voice API specifications.
   *
   * @return a new instance of the default implementation of {@code DiscordFilter}
   */
  static DiscordPlayer voice() {
    return new DiscordPlayerImpl();
  }
}
