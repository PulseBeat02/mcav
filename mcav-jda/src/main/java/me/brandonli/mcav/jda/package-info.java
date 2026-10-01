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
 * Sends pipeline audio to Discord voice channels through JDA.
 *
 * <p>Create a {@link me.brandonli.mcav.jda.DiscordPlayer} with
 * {@link me.brandonli.mcav.jda.DiscordPlayer#voice(net.dv8tion.jda.api.JDA)}, attach it as an audio filter and
 * register it as the guild's sending handler. JDA pulls complete 20 millisecond PCM frames; slow consumption
 * causes the oldest queued frames to be dropped. See {@link me.brandonli.mcav.jda.DiscordPlayer} for an example.
 *
 * <p>The caller logs in the bot, opens and closes its voice connection, releases its media player and shuts
 * down JDA. {@link me.brandonli.mcav.jda.JDAModule} registers the backend but owns none of those resources.
 * The default player's queue is safe for concurrent producers and JDA's consumer; supplied buffers must remain
 * stable while they are read.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.jda;
