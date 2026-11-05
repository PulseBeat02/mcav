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
import java.util.ArrayList;
import java.util.List;

final class DefaultFfmpegCommandBuilder implements FfmpegCommandBuilder {

  private static final String CODEC_NULL_MESSAGE = "Codec must not be null!";
  private static final String BITRATE_NULL_MESSAGE = "Bitrate must not be null!";
  private static final String ARGUMENT_NULL_MESSAGE = "Argument must not be null!";

  private static final String INPUT_FLAG = "-i";
  private static final String VIDEO_CODEC_FLAG = "-c:v";
  private static final String AUDIO_CODEC_FLAG = "-c:a";
  private static final String VIDEO_BITRATE_FLAG = "-b:v";
  private static final String AUDIO_BITRATE_FLAG = "-b:a";
  private static final String FRAME_RATE_FLAG = "-r";
  private static final String RESOLUTION_FLAG = "-s";
  private static final String OVERWRITE_FLAG = "-y";
  private static final String FILTER_COMPLEX_FLAG = "-filter_complex";

  private final List<String> arguments;

  DefaultFfmpegCommandBuilder() {
    this.arguments = new ArrayList<>();
  }

  @Override
  public FfmpegCommandBuilder addInput(final String input) {
    Preconditions.checkNotNull(input, "Input must not be null!");

    this.arguments.add(INPUT_FLAG);
    this.arguments.add(input);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addOutput(final String output) {
    Preconditions.checkNotNull(output, "Output must not be null!");

    this.arguments.add(output);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addVideoCodec(final String codec) {
    Preconditions.checkNotNull(codec, CODEC_NULL_MESSAGE);

    this.arguments.add(VIDEO_CODEC_FLAG);
    this.arguments.add(codec);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addAudioCodec(final String codec) {
    Preconditions.checkNotNull(codec, CODEC_NULL_MESSAGE);

    this.arguments.add(AUDIO_CODEC_FLAG);
    this.arguments.add(codec);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addVideoBitrate(final String bitrate) {
    Preconditions.checkNotNull(bitrate, BITRATE_NULL_MESSAGE);

    this.arguments.add(VIDEO_BITRATE_FLAG);
    this.arguments.add(bitrate);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addAudioBitrate(final String bitrate) {
    Preconditions.checkNotNull(bitrate, BITRATE_NULL_MESSAGE);

    this.arguments.add(AUDIO_BITRATE_FLAG);
    this.arguments.add(bitrate);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addFrameRate(final int frameRate) {
    Preconditions.checkArgument(frameRate > 0, "Frame rate %s must be positive!", frameRate);

    this.arguments.add(FRAME_RATE_FLAG);
    final String value = String.valueOf(frameRate);
    this.arguments.add(value);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addResolution(final int width, final int height) {
    Preconditions.checkArgument(width > 0 && height > 0, "Resolution %sx%s must be positive!", width, height);

    this.arguments.add(RESOLUTION_FLAG);
    final String size = "%sx%s".formatted(width, height);
    this.arguments.add(size);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addOverwrite() {
    this.arguments.add(OVERWRITE_FLAG);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addFilter(final String filter) {
    Preconditions.checkNotNull(filter, "Filter must not be null!");

    this.arguments.add(FILTER_COMPLEX_FLAG);
    this.arguments.add(filter);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addArgument(final String argument) {
    Preconditions.checkNotNull(argument, ARGUMENT_NULL_MESSAGE);

    this.arguments.add(argument);
    return this;
  }

  @Override
  public FfmpegCommandBuilder addArguments(final String... rawArguments) {
    Preconditions.checkNotNull(rawArguments, "Arguments must not be null!");

    for (final String argument : rawArguments) {
      Preconditions.checkNotNull(argument, ARGUMENT_NULL_MESSAGE);
      this.arguments.add(argument);
    }

    return this;
  }

  @Override
  public FfmpegCommand build() {
    return new FfmpegCommand(this.arguments);
  }
}
