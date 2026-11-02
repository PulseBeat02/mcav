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
 * Helpers shared by the commands of the plugin: their arguments, map screens, picture filters, capture devices, disk
 * images, diagnostics and the hand-over of work to the server main thread.
 *
 * <p>{@link me.brandonli.mcav.plugin.utils.ArgumentUtils} parses the command arguments the commands share.
 * {@link me.brandonli.mcav.plugin.utils.MapUtils} builds map screens, and
 * {@link me.brandonli.mcav.plugin.utils.InteractUtils} translates where a player looks on one into pixels.
 * {@link me.brandonli.mcav.plugin.utils.TaskUtils} hands work from worker threads over to the server main thread.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.plugin.utils;
