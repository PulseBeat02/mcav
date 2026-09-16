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

import com.google.common.base.Preconditions;

/**
 * Ready-made FFmpeg commands for common jobs. Every template returns a command that overwrites its output file.
 */
public final class FFmpegTemplates {

  private FFmpegTemplates() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Extracts the audio track of a media file. Experimental encoders of the bundled FFmpeg, such as its built-in
   * {@code vorbis} encoder, are enabled.
   *
   * @param input  the path or URL of the input
   * @param codec  the audio codec to encode with, such as {@code vorbis}, {@code aac}, or {@code libmp3lame}
   * @param output the path of the output file
   * @return the command
   */
  public static FFmpegCommand extractAudio(final String input, final String codec, final String output) {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(codec, "Codec must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addInput(input);
    builder.addArgument("-vn");
    builder.addArguments("-strict", "-2");
    builder.addAudioCodec(codec);
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }

  /**
   * Extracts the audio track of a media file as stereo Ogg Vorbis, the format Minecraft resource packs need.
   * The built-in Vorbis encoder of the bundled FFmpeg only encodes stereo, so mono and surround inputs are
   * mixed to two channels.
   *
   * @param input  the path or URL of the input
   * @param output the path of the {@code .ogg} output file
   * @return the command
   */
  public static FFmpegCommand extractOggVorbis(final String input, final String output) {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addInput(input);
    builder.addArgument("-vn");
    builder.addArguments("-strict", "-2");
    builder.addAudioCodec("vorbis");
    builder.addArguments("-ac", "2");
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }

  /**
   * Re-encodes a video with H.264 video and AAC audio at the specified bitrates.
   *
   * @param input        the path or URL of the input
   * @param output       the path of the output file
   * @param videoBitrate the video bitrate, such as {@code 1000k}
   * @param audioBitrate the audio bitrate, such as {@code 128k}
   * @return the command
   */
  public static FFmpegCommand compressVideo(final String input, final String output, final String videoBitrate, final String audioBitrate) {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    Preconditions.checkNotNull(videoBitrate, "Video bitrate must not be null");
    Preconditions.checkNotNull(audioBitrate, "Audio bitrate must not be null");
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addInput(input);
    builder.addVideoCodec("libx264");
    builder.addBitrate(videoBitrate);
    builder.addAudioCodec("aac");
    builder.addAudioBitrate(audioBitrate);
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }

  /**
   * Cuts a clip out of a media file without re-encoding it.
   *
   * @param input     the path or URL of the input
   * @param output    the path of the output file
   * @param startTime where the clip starts, as seconds or {@code HH:mm:ss}
   * @param duration  how long the clip is, as seconds or {@code HH:mm:ss}
   * @return the command
   */
  public static FFmpegCommand extractClip(final String input, final String output, final String startTime, final String duration) {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    Preconditions.checkNotNull(startTime, "Start time must not be null");
    Preconditions.checkNotNull(duration, "Duration must not be null");
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addArguments("-ss", startTime);
    builder.addInput(input);
    builder.addArguments("-t", duration);
    builder.addVideoCodec("copy");
    builder.addAudioCodec("copy");
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }

  /**
   * Saves a single frame of a video as an image.
   *
   * @param input        the path or URL of the input
   * @param output       the path of the image, whose extension picks the format
   * @param timePosition the position of the frame, as seconds or {@code HH:mm:ss}
   * @return the command
   */
  public static FFmpegCommand createThumbnail(final String input, final String output, final String timePosition) {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    Preconditions.checkNotNull(timePosition, "Time position must not be null");
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addArguments("-ss", timePosition);
    builder.addInput(input);
    builder.addArguments("-frames:v", "1");
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }

  /**
   * Copies the streams of a media file into another container without re-encoding them.
   *
   * @param input  the path or URL of the input
   * @param output the path of the output file, whose extension picks the container
   * @return the command
   */
  public static FFmpegCommand remuxVideo(final String input, final String output) {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addInput(input);
    builder.addVideoCodec("copy");
    builder.addAudioCodec("copy");
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }
}
