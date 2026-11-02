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
/**
 * The audio outputs of the plugin: a Discord bot, the audio web page and Simple Voice Chat speakers.
 *
 * <p>{@link me.brandonli.mcav.plugin.audio.AudioProvider} creates the outputs enabled in {@code config.yml}, and
 * {@link me.brandonli.mcav.plugin.audio.AudioOutputs} tells the sender and the viewers of a command about its audio
 * output, such as the link to the Discord channel or the web page.
 * {@link me.brandonli.mcav.plugin.audio.ServerLoadListener} registers
 * {@link me.brandonli.mcav.plugin.audio.MCAVVoiceChatPlugin} with Simple Voice Chat once the server has loaded.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.plugin.audio;
