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
package me.brandonli.mcav.media.player.multimedia.cv;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;

/**
 * A player that decodes with OpenCV, for files and URLs.
 *
 * <p>OpenCV reads a video file through a backend that has to be compiled into it, and the builds of the bundled
 * JavaCPP presets differ: the Windows and macOS builds have one, the Linux build has none and only captures from
 * cameras. Where OpenCV cannot read files, this player reads them with the FFmpeg reader that JavaCV bundles, so a
 * file plays on every platform instead of a video that never shows a frame. The pipelines, the scaling and the
 * playback clock are the same either way.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class OpenCvPlayer extends AbstractVideoPlayerCv {

  /**
   * Creates an OpenCV player.
   *
   * @since 1.0.0
   */
  public OpenCvPlayer() {}

  /**
   * Creates the grabber for a file or URL, which the player configures and starts: an OpenCV grabber where OpenCV can
   * read files, otherwise the FFmpeg reader that JavaCV bundles.
   *
   * @param resource the file path or URL to decode
   * @return a new, unstarted grabber
   * @throws NullPointerException if the resource is null
   * @since 1.0.0
   */
  @Override
  protected FrameGrabber createFrameGrabber(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");

    final VideoFileReader reader = OpenCvVideoBackends.chooseFileReader();
    return this.createFrameGrabber(resource, reader);
  }

  // Creates the grabber for a resource with either reader. Both cases exist on the platforms the library supports, so
  // both can be tested wherever the tests run.
  @VisibleForTesting
  FrameGrabber createFrameGrabber(final String resource, final VideoFileReader reader) {
    return switch (reader) {
      case OPENCV -> new OpenCVFrameGrabber(resource);
      case FFMPEG -> new FFmpegFrameGrabber(resource);
    };
  }
}
