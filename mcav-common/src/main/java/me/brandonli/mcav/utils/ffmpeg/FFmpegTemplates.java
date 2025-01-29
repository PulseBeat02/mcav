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
package me.brandonli.mcav.utils.ffmpeg;

/**
 * Utility class providing FFmpeg command templates.
 */
public final class FFmpegTemplates {

  private FFmpegTemplates() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Extracts the audio from a video file using the specified audio codec and saves it to the output file.
   *
   * @param input  the path to the input video file from which audio should be extracted
   * @param codec  the audio codec to be used for the extracted audio (e.g., "aac", "mp3")
   * @param output the path to the output file where the extracted audio will be saved
   * @return an {@code FFmpegCommand} object representing the audio extraction command
   */
  public static FFmpegCommand extractAudio(final String input, final String codec, final String output) {
    return FFmpegCommand.builder()
      .addInput(input)
      .addArgument("-vn")
      .addArguments("-strict", "-2")
      .addAudioCodec(codec)
      .addOverwrite()
      .addOutput(output)
      .build();
  }

  /**
   * Creates and returns an FFmpegCommand to compress a video file using specified
   * video and audio bitrates. The output video will use the H.264 codec
   * and the audio will use the AAC codec.
   *
   * @param input        the path to the input video file
   * @param output       the path to save the compressed output video file
   * @param videoBitrate the desired video bitrate for compression (e.g., "1000k")
   * @param audioBitrate the desired audio bitrate for compression (e.g., "128k")
   * @return an FFmpegCommand instance configured for compressing the video with the specified options
   */
  public static FFmpegCommand compressVideo(final String input, final String output, final String videoBitrate, final String audioBitrate) {
    return FFmpegCommand.builder()
      .addInput(input)
      .addVideoCodec("libx264")
      .addBitrate(videoBitrate)
      .addAudioCodec("aac")
      .addAudioBitrate(audioBitrate)
      .addOverwrite()
      .addOutput(output)
      .build();
  }

  /**
   * Creates an FFmpeg command to extract a specific clip from a video file.
   *
   * @param input     The path to the input video file.
   * @param output    The path where the extracted clip will be saved.
   * @param startTime The start time of the clip to extract, specified in the format HH:mm:ss or seconds.
   * @param duration  The duration of the clip to extract, specified in the format HH:mm:ss or seconds.
   * @return An FFmpegCommand configured to extract the specified video clip.
   */
  public static FFmpegCommand extractClip(final String input, final String output, final String startTime, final String duration) {
    return FFmpegCommand.builder()
      .addArguments("-ss", startTime)
      .addInput(input)
      .addArguments("-t", duration)
      .addVideoCodec("copy")
      .addAudioCodec("copy")
      .addOverwrite()
      .addOutput(output)
      .build();
  }

  /**
   * Creates an FFmpeg command to generate a thumbnail from a video file at a specific time position.
   *
   * @param input        the path to the input video file
   * @param output       the path to the output thumbnail image file
   * @param timePosition the timestamp within the video where the thumbnail should be captured, specified in the format "hh:mm:ss"
   * @return an FFmpegCommand object configured to create the thumbnail
   */
  public static FFmpegCommand createThumbnail(final String input, final String output, final String timePosition) {
    return FFmpegCommand.builder()
      .addArguments("-ss", timePosition)
      .addInput(input)
      .addArguments("-frames:v", "1")
      .addOverwrite()
      .addOutput(output)
      .build();
  }

  /**
   * Creates an FFmpeg command for remuxing a video file (repackaging the input
   * without re-encoding or altering the video and audio streams).
   *
   * @param input  the path to the input video file to be remuxed
   * @param output the path to the output video file after remuxing
   * @return an instance of FFmpegCommand configured to remux the input video
   */
  public static FFmpegCommand remuxVideo(final String input, final String output) {
    return FFmpegCommand.builder().addInput(input).addVideoCodec("copy").addAudioCodec("copy").addOverwrite().addOutput(output).build();
  }
}
