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
package me.brandonli.mcav.utils;

import com.google.common.base.Preconditions;
import java.util.Objects;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.source.Source;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Reads the properties of media with FFmpeg without playing it.
 */
public final class MetadataUtils {

  private static final int PROBE_FRAMES = 30;

  private MetadataUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads the size, bitrate, and frame rate of the video track of a source. A few frames are decoded when the
   * container does not declare them, which takes a moment for network sources.
   *
   * @param source the source
   * @return the video metadata
   * @throws InputMetadataException if the source cannot be opened or has no video track
   * @throws NullPointerException   if the source is null
   */
  public static OriginalVideoMetadata parseVideoMetadata(final Source source) {
    Preconditions.checkNotNull(source, "Source must not be null");

    final String resource = source.getResource();
    try (final FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(resource)) {
      grabber.start();
      return readVideoMetadata(grabber, resource);
    } catch (final FrameGrabber.Exception exception) {
      final String message = exception.getMessage();
      throw new InputMetadataException("Failed to read video metadata of " + resource + ": " + message, exception);
    }
  }

  private static OriginalVideoMetadata readVideoMetadata(final FFmpegFrameGrabber grabber, final String resource)
    throws FrameGrabber.Exception {
    final int declaredWidth = grabber.getImageWidth();
    final double declaredFrameRate = grabber.getFrameRate();
    final boolean declared = bothPositive(declaredWidth, declaredFrameRate);
    if (!declared) {
      probe(grabber);
    }

    final int width = grabber.getImageWidth();
    final int height = grabber.getImageHeight();
    final int bitrate = grabber.getVideoBitrate();
    final double frameRate = grabber.getFrameRate();
    final boolean hasVideo = bothPositive(width, height);
    if (!hasVideo) {
      throw new InputMetadataException("Source " + resource + " has no video track");
    }
    return OriginalVideoMetadata.of(width, height, bitrate, (float) frameRate);
  }

  /**
   * Reads the codec, bitrate, sample rate, channel count, and sample format of the audio track of a source.
   *
   * @param source the source
   * @return the audio metadata
   * @throws InputMetadataException if the source cannot be opened or has no audio track
   * @throws NullPointerException   if the source is null
   */
  public static OriginalAudioMetadata parseAudioMetadata(final Source source) {
    Preconditions.checkNotNull(source, "Source must not be null");

    final String resource = source.getResource();
    try (final FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(resource)) {
      grabber.start();
      return readAudioMetadata(grabber, resource);
    } catch (final FrameGrabber.Exception exception) {
      final String message = exception.getMessage();
      throw new InputMetadataException("Failed to read audio metadata of " + resource + ": " + message, exception);
    }
  }

  private static OriginalAudioMetadata readAudioMetadata(final FFmpegFrameGrabber grabber, final String resource)
    throws FrameGrabber.Exception {
    final int declaredSampleRate = grabber.getSampleRate();
    final int declaredChannels = grabber.getAudioChannels();
    final boolean declared = bothPositive(declaredSampleRate, declaredChannels);
    if (!declared) {
      probe(grabber);
    }

    final String codec = grabber.getAudioCodecName();
    final int bitrate = grabber.getAudioBitrate();
    final int sampleRate = grabber.getSampleRate();
    final int channels = grabber.getAudioChannels();
    final int format = grabber.getSampleFormat();
    final boolean hasAudio = bothPositive(sampleRate, channels);
    if (!hasAudio) {
      throw new InputMetadataException("Source " + resource + " has no audio track");
    }
    final String codecName = Objects.requireNonNullElse(codec, "unknown");
    return OriginalAudioMetadata.of(codecName, bitrate, sampleRate, channels, format);
  }

  private static boolean bothPositive(final double first, final double second) {
    final double smaller = Math.min(first, second);
    return smaller > 0;
  }

  private static void probe(final FFmpegFrameGrabber grabber) throws FrameGrabber.Exception {
    for (int frameIndex = 0; frameIndex < PROBE_FRAMES; frameIndex++) {
      final Frame frame = grabber.grabFrame();
      if (frame == null) {
        return;
      }
    }
  }
}
