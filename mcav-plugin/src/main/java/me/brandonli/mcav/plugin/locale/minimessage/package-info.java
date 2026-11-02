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
 * The Adventure translator of the plugin, which renders messages written in MiniMessage.
 *
 * <p>{@link me.brandonli.mcav.plugin.locale.minimessage.PluginTranslator} renders the messages of the configured
 * language and extends {@link me.brandonli.mcav.plugin.locale.minimessage.MiniMessageTranslator}.
 * {@link me.brandonli.mcav.plugin.locale.minimessage.ArgumentTag} is the tag {@code <arg:N>} that inserts the
 * arguments of a message.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.plugin.locale.minimessage;
