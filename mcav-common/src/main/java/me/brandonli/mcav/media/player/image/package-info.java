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
 * Plays application-generated or predecoded frames on a dedicated daemon thread.
 *
 * <p>Create an {@link me.brandonli.mcav.media.player.image.ImagePlayer}, attach its video pipeline and start a
 * {@link me.brandonli.mcav.media.source.frame.FrameSource}. Starting returns after launching the worker; it does
 * not wait for the first frame. The worker skips arrays whose size does not match the source dimensions, copies
 * valid arrays into its reusable native image, and reports ordinary supplier or filter failures to its handler.
 * Callbacks borrow the image for that invocation and must copy it if they need to retain it.
 *
 * <p>The default player serializes start and release. Release is terminal, interrupts the worker and waits up to
 * two seconds when called externally. It does not close a supplied animation or release attached filters. Keep
 * those resources available until any callback that outlived the bounded wait has actually returned.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.player.image;
