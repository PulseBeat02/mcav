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
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class FfmpegTemplates {

  private static final String INPUT_NULL_MESSAGE = "Input must not be null!";
  private static final String OUTPUT_NULL_MESSAGE = "Output must not be null!";

  private static final String NO_VIDEO_FLAG = "-vn";
  private static final String SEEK_FLAG = "-ss";
  private static final String STRICTNESS_FLAG = "-strict";
  private static final String EXPERIMENTAL_STRICTNESS = "-2";

  private static final String VORBIS_CODEC = "vorbis";
  private static final String H264_CODEC = "libx264";
  private static final String AAC_CODEC = "aac";
  private static final String COPY_CODEC = "copy";

  private static final String STEREO_CHANNELS = "2";
  private static final String SINGLE_FRAME = "1";

  private FfmpegTemplates() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Extracts the audio track of a media file. Experimental encoders of the bundled FFmpeg, such as its built-in
   * {@code vorbis} encoder, are enabled.
   *
   * @param input  the path or URL of the input
   * @param codec  the audio codec to encode with, such as {@code vorbis}, {@code aac}, or {@code libmp3lame}
   * @param output the path of the output file
   * @return the command
   * @throws NullPointerException if {@code input}, {@code codec} or {@code output} is null
   * @since 1.0.0
   */
  public static FfmpegCommand extractAudio(final String input, final String codec, final String output) {
    Preconditions.checkNotNull(input, INPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(codec, "Codec must not be null!");
    Preconditions.checkNotNull(output, OUTPUT_NULL_MESSAGE);

    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addInput(input);
    builder.addArgument(NO_VIDEO_FLAG);
    builder.addArguments(STRICTNESS_FLAG, EXPERIMENTAL_STRICTNESS);
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
   * @throws NullPointerException if {@code input} or {@code output} is null
   * @since 1.0.0
   */
  public static FfmpegCommand extractOggVorbis(final String input, final String output) {
    Preconditions.checkNotNull(input, INPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(output, OUTPUT_NULL_MESSAGE);

    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addInput(input);
    builder.addArgument(NO_VIDEO_FLAG);
    builder.addArguments(STRICTNESS_FLAG, EXPERIMENTAL_STRICTNESS);
    builder.addAudioCodec(VORBIS_CODEC);
    builder.addArguments("-ac", STEREO_CHANNELS);
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
   * @throws NullPointerException if {@code input}, {@code output}, {@code videoBitrate} or {@code audioBitrate} is null
   * @since 1.0.0
   */
  public static FfmpegCommand compressVideo(final String input, final String output, final String videoBitrate, final String audioBitrate) {
    Preconditions.checkNotNull(input, INPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(output, OUTPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(videoBitrate, "Video bitrate must not be null!");
    Preconditions.checkNotNull(audioBitrate, "Audio bitrate must not be null!");

    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addInput(input);
    builder.addVideoCodec(H264_CODEC);
    builder.addVideoBitrate(videoBitrate);
    builder.addAudioCodec(AAC_CODEC);
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
   * @throws NullPointerException if {@code input}, {@code output}, {@code startTime} or {@code duration} is null
   * @since 1.0.0
   */
  public static FfmpegCommand extractClip(final String input, final String output, final String startTime, final String duration) {
    Preconditions.checkNotNull(input, INPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(output, OUTPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(startTime, "Start time must not be null!");
    Preconditions.checkNotNull(duration, "Duration must not be null!");

    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addArguments(SEEK_FLAG, startTime);
    builder.addInput(input);
    builder.addArguments("-t", duration);
    builder.addVideoCodec(COPY_CODEC);
    builder.addAudioCodec(COPY_CODEC);
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
   * @throws NullPointerException if {@code input}, {@code output} or {@code timePosition} is null
   * @since 1.0.0
   */
  public static FfmpegCommand createThumbnail(final String input, final String output, final String timePosition) {
    Preconditions.checkNotNull(input, INPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(output, OUTPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(timePosition, "Time position must not be null!");

    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addArguments(SEEK_FLAG, timePosition);
    builder.addInput(input);
    builder.addArguments("-frames:v", SINGLE_FRAME);
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
   * @throws NullPointerException if {@code input} or {@code output} is null
   * @since 1.0.0
   */
  public static FfmpegCommand remuxVideo(final String input, final String output) {
    Preconditions.checkNotNull(input, INPUT_NULL_MESSAGE);
    Preconditions.checkNotNull(output, OUTPUT_NULL_MESSAGE);

    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addInput(input);
    builder.addVideoCodec(COPY_CODEC);
    builder.addAudioCodec(COPY_CODEC);
    builder.addOverwrite();
    builder.addOutput(output);
    return builder.build();
  }
}
