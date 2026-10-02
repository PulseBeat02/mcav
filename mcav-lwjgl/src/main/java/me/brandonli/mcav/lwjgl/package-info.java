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
 * Bridges pipeline video frames to an OpenGL texture through LWJGL.
 *
 * <p>{@link me.brandonli.mcav.lwjgl.GLTextureFilter} copies packed BGR frames on a producer thread and uploads
 * the most recent one when the render thread calls {@link me.brandonli.mcav.lwjgl.GLTextureFilter#upload()}.
 * Its class documentation shows the setup. The render thread must keep the context current and initialize
 * LWJGL capabilities before starting, uploading or deleting an owned texture.
 *
 * <p>The application owns the context and any texture passed to the filter's constructor. Stop producers before
 * releasing the filter on the render thread. {@link me.brandonli.mcav.lwjgl.LWJGLModule} does not create or
 * release contexts or filters.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.lwjgl;
