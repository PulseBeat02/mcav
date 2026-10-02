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
 * Builds and executes command-line FFmpeg jobs using the bundled platform executable.
 *
 * <p>{@link me.brandonli.mcav.utils.ffmpeg.FFmpegCommand} stores an immutable argument snapshot. Its builder is
 * mutable and not thread-safe; input and output option order matters. Arguments are separate process tokens,
 * not shell expressions, so paths with spaces need no added quotes. Templates build commands without running them.
 * Resolving an executable or creating a task can extract native resources on first use.
 *
 * <p>{@link me.brandonli.mcav.utils.ffmpeg.AudioExtractor} executes synchronously and returns a new cache file
 * owned by the caller. A failure may leave a partial output. Templates allow output replacement, so callers choose
 * destinations and coordinate concurrent writers. Use a task with an explicit timeout when execution must be bounded.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.ffmpeg;
