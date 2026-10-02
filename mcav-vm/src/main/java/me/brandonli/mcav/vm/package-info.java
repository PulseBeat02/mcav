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
 * Runs caller-configured QEMU virtual machines and streams their video and supported guest audio.
 *
 * <p>Use {@link me.brandonli.mcav.vm.VMConfiguration} for QEMU arguments,
 * {@link me.brandonli.mcav.vm.VMSettings} for output dimensions, update rate and VNC port, and
 * {@link me.brandonli.mcav.vm.VMPlayer} for playback and input. QEMU must be available on the host;
 * {@link me.brandonli.mcav.vm.VMModule} only checks its availability and does not install or run it.
 * The player owns its QEMU process and local VNC connections, and reserves their display/audio options.
 *
 * <p>Configuration objects are mutable and not thread-safe; finish them before starting the player.
 * Settings are immutable. Video and audio pipelines run on background threads. Pause suppresses output
 * while the guest continues running. Release each player to terminate its owned resources; module shutdown
 * does not release players, and attached filters retain their separate lifetimes.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.vm;
