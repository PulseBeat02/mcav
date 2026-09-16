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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.utils.runtime.CommandTask;

/**
 * An FFmpeg command line, built with {@link #builder()} and run through the task of {@link #createTask()}.
 *
 * <pre><code>
 *   final FFmpegCommand.Builder builder = FFmpegCommand.builder();
 *   builder.addInput("input.mp4");
 *   builder.addAudioCodec("libvorbis");
 *   builder.addOverwrite();
 *   builder.addOutput("output.ogg");
 *   final FFmpegCommand command = builder.build();
 *   final CommandTask task = command.createTask();
 *   final int exitCode = task.run();
 * </code></pre>
 */
public final class FFmpegCommand {

  private final List<String> arguments;

  FFmpegCommand(final List<String> arguments) {
    this.arguments = List.copyOf(arguments);
  }

  /**
   * Creates a builder for a command.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the arguments after the executable.
   *
   * @return the arguments, which cannot be modified
   */
  public List<String> getArguments() {
    return this.arguments;
  }

  /**
   * Gets the FFmpeg executable the command runs with.
   *
   * @return the path of the executable
   */
  public Path getExecutable() {
    return FFmpegExecutableProvider.getFFmpegPath();
  }

  /**
   * Gets the complete command line, starting with the executable.
   *
   * @return the command line as separate arguments
   */
  public String[] toCommandArray() {
    final Path executable = this.getExecutable();
    final String executablePath = executable.toString();
    final int argumentCount = this.arguments.size();
    final List<String> command = new ArrayList<>(argumentCount + 1);
    command.add(executablePath);
    command.addAll(this.arguments);
    return command.toArray(new String[0]);
  }

  /**
   * Creates a task for the command without starting it. Call {@link CommandTask#run()} or
   * {@link CommandTask#run(java.time.Duration)} to run it and wait for the exit code.
   *
   * @return the task, which has not been run yet
   */
  public CommandTask createTask() {
    final String[] command = this.toCommandArray();
    return new CommandTask(command);
  }

  @Override
  public String toString() {
    final String[] command = this.toCommandArray();
    return String.join(" ", command);
  }

  /**
   * Collects the arguments of an {@link FFmpegCommand}. Arguments are emitted in the order they are added, which
   * matters to FFmpeg: options apply to the next input or output file. Every method returns this builder.
   */
  public static final class Builder {

    private final List<String> arguments;

    Builder() {
      this.arguments = new ArrayList<>();
    }

    /**
     * Adds an input file or URL ({@code -i}).
     *
     * @param input the input
     * @return this builder
     */
    public Builder addInput(final String input) {
      Preconditions.checkNotNull(input, "Input must not be null");
      this.arguments.add("-i");
      this.arguments.add(input);
      return this;
    }

    /**
     * Adds an output file. Output options must be added before the output they apply to.
     *
     * @param output the output path
     * @return this builder
     */
    public Builder addOutput(final String output) {
      Preconditions.checkNotNull(output, "Output must not be null");
      this.arguments.add(output);
      return this;
    }

    /**
     * Sets the video codec ({@code -c:v}).
     *
     * @param codec the codec name, or {@code copy} to keep the stream
     * @return this builder
     */
    public Builder addVideoCodec(final String codec) {
      Preconditions.checkNotNull(codec, "Codec must not be null");
      this.arguments.add("-c:v");
      this.arguments.add(codec);
      return this;
    }

    /**
     * Sets the audio codec ({@code -c:a}).
     *
     * @param codec the codec name, or {@code copy} to keep the stream
     * @return this builder
     */
    public Builder addAudioCodec(final String codec) {
      Preconditions.checkNotNull(codec, "Codec must not be null");
      this.arguments.add("-c:a");
      this.arguments.add(codec);
      return this;
    }

    /**
     * Sets the video bitrate ({@code -b:v}).
     *
     * @param bitrate the bitrate, such as {@code 1000k}
     * @return this builder
     */
    public Builder addBitrate(final String bitrate) {
      Preconditions.checkNotNull(bitrate, "Bitrate must not be null");
      this.arguments.add("-b:v");
      this.arguments.add(bitrate);
      return this;
    }

    /**
     * Sets the audio bitrate ({@code -b:a}).
     *
     * @param bitrate the bitrate, such as {@code 128k}
     * @return this builder
     */
    public Builder addAudioBitrate(final String bitrate) {
      Preconditions.checkNotNull(bitrate, "Bitrate must not be null");
      this.arguments.add("-b:a");
      this.arguments.add(bitrate);
      return this;
    }

    /**
     * Sets the frame rate ({@code -r}).
     *
     * @param framerate the frame rate in frames per second
     * @return this builder
     */
    public Builder addFramerate(final int framerate) {
      Preconditions.checkArgument(framerate > 0, "Frame rate must be positive but was %s", framerate);
      this.arguments.add("-r");
      final String value = String.valueOf(framerate);
      this.arguments.add(value);
      return this;
    }

    /**
     * Sets the frame size ({@code -s}).
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @return this builder
     */
    public Builder addResolution(final int width, final int height) {
      Preconditions.checkArgument(width > 0 && height > 0, "Resolution must be positive but was %sx%s", width, height);
      this.arguments.add("-s");
      final String size = width + "x" + height;
      this.arguments.add(size);
      return this;
    }

    /**
     * Allows FFmpeg to overwrite an existing output file ({@code -y}). Without it FFmpeg waits for confirmation
     * on the console and the task hangs.
     * @return this builder
     */
    public Builder addOverwrite() {
      this.arguments.add("-y");
      return this;
    }

    /**
     * Adds a filter graph ({@code -filter_complex}).
     *
     * @param filter the filter graph
     * @return this builder
     */
    public Builder addFilter(final String filter) {
      Preconditions.checkNotNull(filter, "Filter must not be null");
      this.arguments.add("-filter_complex");
      this.arguments.add(filter);
      return this;
    }

    /**
     * Adds a raw argument.
     *
     * @param argument the argument
     * @return this builder
     */
    public Builder addArgument(final String argument) {
      Preconditions.checkNotNull(argument, "Argument must not be null");
      this.arguments.add(argument);
      return this;
    }

    /**
     * Adds raw arguments.
     *
     * @param rawArguments the arguments in order
     * @return this builder
     */
    public Builder addArguments(final String... rawArguments) {
      Preconditions.checkNotNull(rawArguments, "Arguments must not be null");
      for (final String argument : rawArguments) {
        Preconditions.checkNotNull(argument, "Argument must not be null");
        this.arguments.add(argument);
      }
      return this;
    }

    /**
     * Builds the command.
     *
     * @return the command
     */
    public FFmpegCommand build() {
      return new FFmpegCommand(this.arguments);
    }
  }
}
