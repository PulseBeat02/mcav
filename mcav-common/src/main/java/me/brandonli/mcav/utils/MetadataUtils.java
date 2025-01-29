/*
 * This file is part of mcav, a media playback library for Minecraft
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
 * Utility class providing methods for parsing and retrieving metadata from multimedia sources.
 * This class is designed for working with video and audio files by using FFmpegFrameGrabber
 * to extract relevant metadata properties.
 * <p>
 * This is a final class and cannot be instantiated.
 */
public final class MetadataUtils {

  private MetadataUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Parses video metadata from the given {@code Source} object. The method extracts
   * essential information such as video width, height, bitrate, and frame rate from the
   * resource represented by the {@code Source}.
   *
   * @param source the {@code Source} object representing the video resource to be parsed
   * @return a {@code VideoMetadata} instance containing the extracted metadata
   * @throws AssertionError if a {@code FrameGrabber.Exception} occurs during the process
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
      throw new InputMetadataException(e.getMessage());
    }
  }

  /**
   * Extracts audio metadata from the provided source by analyzing the associated resource.
   * Metadata includes information such as audio bitrate, sample rate, and number of channels.
   *
   * @param source the source representing the audio resource to be parsed
   * @return an {@code AudioMetadata} instance containing the parsed audio metadata, including bitrate, sample rate, and channels
   * @throws AssertionError if an error occurs while processing the audio resource
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
      final int bitrate = grabber.getAudioBitrate();
      final int sampleRate = grabber.getSampleRate();
      final int channels = grabber.getAudioChannels();
      grabber.close();
      return AudioMetadata.of(bitrate, sampleRate, channels);
    } catch (final FrameGrabber.Exception e) {
      throw new InputMetadataException(e.getMessage());
    }
  }
}
