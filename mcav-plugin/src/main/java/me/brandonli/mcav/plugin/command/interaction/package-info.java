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
 * The interactive commands {@code /mcav browser}, {@code /mcav vm} and {@code /mcav vnc}, which show a web page, a
 * QEMU virtual machine or the desktop of a VNC server on a map screen.
 *
 * <p>{@link me.brandonli.mcav.plugin.command.interaction.AbstractInteractiveCommand} holds what they share: it shows
 * the interactive player on a map screen and forwards clicks on the screen and the chat messages of players who
 * enabled interaction. {@link me.brandonli.mcav.plugin.command.interaction.VncAllowList} names the VNC servers
 * {@code /mcav vnc create} may connect to.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.plugin.command.interaction;
