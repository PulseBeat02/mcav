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
 * <p>Use {@link me.brandonli.mcav.vm.VmConfiguration} for QEMU arguments,
 * {@link me.brandonli.mcav.vm.VmSettings} for output dimensions, update rate and VNC port, and
 * {@link me.brandonli.mcav.vm.VmPlayer} for playback and input. The player uses the QEMU of the host when it finds
 * one; otherwise its first start installs the Debian 12 packages of the QEMU of the guest into the MCAV cache
 * folder, on x86-64 and ARM64 Linux only. On other platforms, QEMU must be installed on the host.
 * {@link me.brandonli.mcav.vm.VmModule} only checks whether QEMU is available and does not install or run it.
 * The player owns its QEMU process and local VNC connections, and reserves their display and audio options.
 * Failures of QEMU and the machine are reported as {@link me.brandonli.mcav.vm.VmException}.
 *
 * <p>Configurations and settings are immutable; configuration builders are not thread-safe. Video and audio
 * pipelines run on background threads. Pause suppresses output while the guest continues running. Release each
 * player to terminate its owned resources; module shutdown does not release players, and attached filters retain
 * their separate lifetimes.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.vm;
