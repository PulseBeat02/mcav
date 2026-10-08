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
package me.brandonli.mcav.sandbox.utils;

/**
 * The {@code <audioType>} argument of the {@code /mcav video} commands: where the sound of the video is played.
 *
 * <p>Minecraft cannot play arbitrary audio by itself, so the sound goes through one of these outputs. Each output
 * other than {@link #NONE} and {@link #SIMPLE_VOICE_CHAT} needs its section of {@code config.yml} filled in and
 * enabled; if it is not, the command answers that the output is unsupported. The Discord bot and the web page start
 * in the background when the plugin is enabled, and until they are ready the command answers that the output is
 * not ready yet.
 */
public enum AudioArgument {
  /**
   * No sound: the video plays silently. Needs no configuration and always works, which makes it useful to test a
   * video before setting up an audio output.
   */
  NONE,

  /**
   * Plays the sound through a Discord bot in a voice channel. Needs the {@code discord-bot} section of
   * {@code config.yml} with {@code enabled: true}, the bot {@code token}, the {@code guild-id} of the Discord server,
   * and the {@code channel-id} of the voice channel; the bot must be invited to the server with permission to join
   * and speak in that channel. When the video starts, the bot joins the channel and the viewers get a clickable link
   * to join it. Players hear the sound in Discord, not in the game, so it is not positional.
   */
  DISCORD_BOT,

  /**
   * Plays the sound on a web page served by the plugin. Needs the {@code http-server} section of {@code config.yml}
   * with {@code enabled: true}, a {@code host-name} that players can reach, such as the public address of the
   * server, and a {@code port} that is open in the firewall. When the video starts, the viewers get a clickable link
   * to the page, which plays the sound in their browser. It works for any player with a browser, but the sound is
   * not positional and may lag slightly behind the picture.
   */
  HTTP_SERVER,

  /**
   * Plays the sound in the game through the Simple Voice Chat mod, as if it came from each of the selected
   * players. Needs the Simple Voice Chat plugin ({@code voicechat}) installed on the server and
   * {@code simple-voice-chat.enabled: true} in {@code config.yml}. When this option is on but Simple Voice Chat is
   * missing, the plugin logs one error that names the problem and how to fix it, and then disables itself. Only
   * players with the Simple Voice Chat mod installed hear the sound, with positional audio within 32 blocks of the
   * selected players.
   */
  SIMPLE_VOICE_CHAT,
}
