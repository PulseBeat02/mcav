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
package me.brandonli.mcav.utils.ffmpeg;

import java.nio.file.Path;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;

/**
 * Locates the FFmpeg command-line executable bundled with JavaCV. The executable is extracted from the platform
 * jar the first time it is requested, so the first call may take a moment.
 *
 * <p>On a Linux machine that lacks a system library that FFmpeg links, such as ALSA or PulseAudio, the first call also
 * installs the pinned Debian packages of those libraries into the cache folder of MCAV and loads them, which needs a
 * network connection once. On Linux the executable needs its own folder and the folders of those libraries on its
 * library path: run it with the environment variables of {@link FfmpegCommand#getEnvironment()}, or through
 * {@link FfmpegCommand#createTask()}, which sets them.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class FfmpegExecutableProvider {

  private FfmpegExecutableProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Returns the path of the FFmpeg executable, extracting it on first use.
   *
   * @return the path of the executable
   * @throws UnsatisfiedLinkError if JavaCV does not ship FFmpeg for this platform
   * @since 1.0.0
   */
  public static Path getFfmpegPath() {
    return ExecutableHolder.EXECUTABLE;
  }

  private static final class ExecutableHolder {

    private static final Path EXECUTABLE = extract();

    private ExecutableHolder() {
      throw new UnsupportedOperationException("Holder class cannot be instantiated!");
    }

    private static Path extract() {
      final FfmpegLibraries _ = FfmpegLibraries.installOnce();
      final String location = Loader.load(ffmpeg.class);
      return Path.of(location);
    }
  }
}
