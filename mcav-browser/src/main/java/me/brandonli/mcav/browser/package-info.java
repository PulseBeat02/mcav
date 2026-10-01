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
 * Renders web pages in isolated JCEF helper processes and delivers their video and capturable audio to pipelines.
 *
 * <p>Create a {@link me.brandonli.mcav.browser.BrowserSource} for a web address and a
 * {@link me.brandonli.mcav.browser.BrowserPlayer}, optionally supplying immutable
 * {@link me.brandonli.mcav.browser.BrowserOptions}. The player's class documentation shows setup, input and
 * content-policy choices. Sources and options are immutable; option builders are mutable and not thread-safe.
 * Installation of pinned native dependencies is deferred until a player starts.
 *
 * <p>The caller releases players when finished. {@link me.brandonli.mcav.browser.BrowserModule#stop()} also
 * terminates registered helpers and prevents new starts until the module starts again. Pipelines and filters
 * remain caller-owned; background callbacks borrow image/audio data for the duration of each call.
 * {@link me.brandonli.mcav.browser.BrowserHelper} is public only as the child JVM's entry point and must not
 * be run inside the host JVM, because its main method exits that JVM.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.browser;
