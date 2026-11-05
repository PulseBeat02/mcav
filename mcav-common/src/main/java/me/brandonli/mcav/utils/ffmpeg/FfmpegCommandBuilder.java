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

/**
 * Collects the arguments of an {@link FfmpegCommand}. Arguments are emitted in the order they are added, which
 * matters to FFmpeg: options apply to the next input or output file. Every method returns this builder.
 *
 * <p>Builders are not thread-safe; use one per thread.
 *
 * @since 1.0.0
 */
public interface FfmpegCommandBuilder {
  /**
   * Adds an input file or URL ({@code -i}).
   *
   * @param input the input
   * @return this builder
   * @throws NullPointerException if {@code input} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addInput(final String input);

  /**
   * Adds an output file. Output options must be added before the output they apply to.
   *
   * @param output the output path
   * @return this builder
   * @throws NullPointerException if {@code output} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addOutput(final String output);

  /**
   * Sets the video codec ({@code -c:v}).
   *
   * @param codec the codec name, or {@code copy} to keep the stream
   * @return this builder
   * @throws NullPointerException if {@code codec} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addVideoCodec(final String codec);

  /**
   * Sets the audio codec ({@code -c:a}).
   *
   * @param codec the codec name, or {@code copy} to keep the stream
   * @return this builder
   * @throws NullPointerException if {@code codec} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addAudioCodec(final String codec);

  /**
   * Sets the video bitrate ({@code -b:v}).
   *
   * @param bitrate the bitrate, such as {@code 1000k}
   * @return this builder
   * @throws NullPointerException if {@code bitrate} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addVideoBitrate(final String bitrate);

  /**
   * Sets the audio bitrate ({@code -b:a}).
   *
   * @param bitrate the bitrate, such as {@code 128k}
   * @return this builder
   * @throws NullPointerException if {@code bitrate} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addAudioBitrate(final String bitrate);

  /**
   * Sets the frame rate ({@code -r}).
   *
   * @param frameRate the frame rate in frames per second
   * @return this builder
   * @throws IllegalArgumentException if the frame rate is not positive
   * @since 1.0.0
   */
  FfmpegCommandBuilder addFrameRate(final int frameRate);

  /**
   * Sets the frame size ({@code -s}).
   *
   * @param width  the width in pixels
   * @param height the height in pixels
   * @return this builder
   * @throws IllegalArgumentException if either dimension is not positive
   * @since 1.0.0
   */
  FfmpegCommandBuilder addResolution(final int width, final int height);

  /**
   * Allows FFmpeg to overwrite an existing output file ({@code -y}). Without it FFmpeg waits for confirmation
   * on the console and the task hangs.
   *
   * @return this builder
   * @since 1.0.0
   */
  FfmpegCommandBuilder addOverwrite();

  /**
   * Adds a filter graph ({@code -filter_complex}).
   *
   * @param filter the filter graph
   * @return this builder
   * @throws NullPointerException if {@code filter} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addFilter(final String filter);

  /**
   * Adds a raw argument.
   *
   * @param argument the argument
   * @return this builder
   * @throws NullPointerException if {@code argument} is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addArgument(final String argument);

  /**
   * Adds raw arguments.
   *
   * @param rawArguments the arguments in order, none of them null; the ones before a null element are already added
   *                     when the check fails
   * @return this builder
   * @throws NullPointerException if the array or any argument is null
   * @since 1.0.0
   */
  FfmpegCommandBuilder addArguments(final String... rawArguments);

  /**
   * Builds the command.
   *
   * @return a new immutable snapshot; subsequent builder changes do not affect it
   * @since 1.0.0
   */
  FfmpegCommand build();
}
