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

import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.source.Source;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Utility class providing methods for parsing and retrieving metadata from media sources.
 */
public final class MetadataUtils {

  private MetadataUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Extracts video metadata from the provided source by analyzing the associated resource.
   * Metadata includes information such as width, height, bitrate, and frame rate.
   *
   * @param source the source representing the video resource to be parsed
   * @return a {@code VideoMetadata} instance containing the parsed video metadata, including width, height, bitrate, and frame rate
   * @throws AssertionError if an error occurs while processing the video resource
   */
  public static VideoMetadata parseVideoMetadata(final Source source) {
    final String resource = source.getResource();
    try {
      final FrameGrabber grabber = new FFmpegFrameGrabber(resource);
      grabber.start();
      int count = 0;
      while (grabber.grabFrame() != null && count < 30) {
        count++; // ensure right frame data
      }
      final int width = grabber.getImageWidth();
      final int height = grabber.getImageHeight();
      final int bitrate = grabber.getVideoBitrate();
      final float frameRate = (float) grabber.getFrameRate();

      grabber.close();
      return VideoMetadata.of(width, height, bitrate, frameRate);
    } catch (final FrameGrabber.Exception e) {
      throw new InputMetadataException(e.getMessage(), e);
    }
  }

  /**
   * Extracts video metadata from the provided source by analyzing the associated resource.
   * Metadata includes information such as width, height, bitrate, and frame rate.
   *
   * @param source the source representing the video resource to be parsed
   * @return a {@code VideoMetadata} instance containing the parsed video metadata, including width, height, bitrate, and frame rate
   * @throws AssertionError if an error occurs while processing the video resource
   */
  public static AudioMetadata parseAudioMetadata(final Source source) {
    final String resource = source.getResource();
    try {
      final FrameGrabber grabber = new FFmpegFrameGrabber(resource);
      grabber.start();
      int count = 0;
      while (grabber.grabFrame() != null && count < 30) {
        count++; // ensure right frame data
      }
      final String codec = grabber.getAudioCodecName();
      final int bitrate = grabber.getAudioBitrate();
      final int sampleRate = grabber.getSampleRate();
      final int channels = grabber.getAudioChannels();
      grabber.close();
      return AudioMetadata.of(codec, bitrate, sampleRate, channels);
    } catch (final FrameGrabber.Exception e) {
      throw new InputMetadataException(e.getMessage(), e);
    }
  }
}
