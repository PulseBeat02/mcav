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
 * Bridges JavaCV frame planes, packed ARGB arrays and OpenCV native values.
 *
 * <p>{@link me.brandonli.mcav.utils.opencv.FramePixels} assumes a frame already decoded to 8-bit BGR and strips
 * row padding. The source plane and destination buffer remain caller-owned; position and limit are preserved.
 * The dimensions and stride must describe addressable bytes, with packed size fitting a Java buffer.
 *
 * <p>{@link me.brandonli.mcav.utils.opencv.ImageUtils#toScalar(double[])} copies components into a new native
 * scalar that the caller closes when no longer needed. Resizing pixel arrays uses temporary native images that
 * the helper releases before returning a new opaque ARGB array. Helpers can run concurrently on independent inputs;
 * callers prevent mutation or release of borrowed inputs during a call.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.opencv;
