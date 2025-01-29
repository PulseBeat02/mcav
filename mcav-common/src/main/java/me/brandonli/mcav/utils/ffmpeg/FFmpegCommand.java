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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.utils.runtime.CommandTask;

/**
 * Represents a command to be executed using FFmpeg.
 */
public final class FFmpegCommand {

  private final List<String> arguments;
  private final Path executable;

  private FFmpegCommand(final List<String> arguments) {
    this.arguments = arguments;
    this.executable = FFmpegExecutableProvider.getFFmpegPath();
  }

  /**
   * Retrieves the list of arguments associated with this FFmpeg command.
   *
   * @return a list of strings containing the arguments for the FFmpeg command
   */
  public List<String> getArguments() {
    return new ArrayList<>(this.arguments);
  }

  /**
   * Retrieves the path to the executable associated with this FFmpegCommand instance.
   *
   * @return the path to the FFmpeg executable as a {@code Path} object
   */
  public Path getExecutable() {
    return this.executable;
  }

  /**
   * Converts the executable path and its associated arguments into an array of strings
   * representing the full command to be executed.
   *
   * @return an array of strings where the first element is the executable path
   * and the subsequent elements are the command arguments
   */
  public String[] toCommandArray() {
    final List<String> command = new ArrayList<>();
    command.add(this.executable.toString());
    command.addAll(this.arguments);
    return command.toArray(new String[0]);
  }

  /**
   * Executes the command represented by this instance and creates a {@link CommandTask}.
   *
   * @return a {@link CommandTask} instance representing the execution of the command
   * @throws IOException if an I/O error occurs during the initialization or execution of the command
   */
  public CommandTask execute() throws IOException {
    final String[] args = this.toCommandArray();
    return new CommandTask(args, true);
  }

  /**
   * Creates and returns a new instance of the {@code Builder} class for constructing an {@code FFmpegCommand}.
   *
   * @return a new {@code Builder} instance for creating an {@code FFmpegCommand}
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder class for constructing an {@code FFmpegCommand} with various options and arguments.
   */
  public static final class Builder {

    private final List<String> arguments = new ArrayList<>();

    private Builder() {}

    /**
     * Adds an input file to the FFmpeg command.
     *
     * @param input the path to the input file to be added
     * @return the {@code Builder} instance to allow method chaining
     */
    public Builder addInput(final String input) {
      this.arguments.add("-i");
      this.arguments.add(input);
      return this;
    }

    /**
     * Adds an output file path to the argument list for the FFmpeg command.
     *
     * @param output the path to the output file
     * @return this builder instance for chaining additional method calls
     */
    public Builder addOutput(final String output) {
      this.arguments.add(output);
      return this;
    }

    /**
     * Adds a video codec argument to the FFmpeg command.
     *
     * @param codec the video codec to use (e.g., "libx264", "copy", etc.)
     * @return the current {@code Builder} instance for chaining further method calls
     */
    public Builder addVideoCodec(final String codec) {
      this.arguments.add("-c:v");
      this.arguments.add(codec);
      return this;
    }

    /**
     * Adds an audio codec to the FFmpeg command.
     *
     * @param codec the name of the audio codec to be used (e.g., "aac", "mp3")
     * @return the current {@code Builder} instance for method chaining
     */
    public Builder addAudioCodec(final String codec) {
      this.arguments.add("-c:a");
      this.arguments.add(codec);
      return this;
    }

    /**
     * Adds the specified video bitrate option to the FFmpeg command.
     *
     * @param bitrate the video bitrate to be added, e.g., "1000k" for 1000 kilobits per second
     * @return the {@code Builder} instance for method chaining
     */
    public Builder addBitrate(final String bitrate) {
      this.arguments.add("-b:v");
      this.arguments.add(bitrate);
      return this;
    }

    /**
     * Adds an audio bitrate setting to the FFmpeg command being constructed.
     *
     * @param bitrate the desired audio bitrate, specified as a string (e.g., "128k" for 128 kbps)
     * @return the {@code Builder} instance, allowing for method chaining
     */
    public Builder addAudioBitrate(final String bitrate) {
      this.arguments.add("-b:a");
      this.arguments.add(bitrate);
      return this;
    }

    /**
     * Adds a framerate option to the FFmpeg command being built.
     *
     * @param framerate the desired framerate for the video (e.g., 24, 30, 60)
     * @return the current Builder instance for method chaining
     */
    public Builder addFramerate(final int framerate) {
      this.arguments.add("-r");
      this.arguments.add(String.valueOf(framerate));
      return this;
    }

    /**
     * Adds a resolution argument to the FFmpeg command being built.
     *
     * @param width  the width of the resolution in pixels
     * @param height the height of the resolution in pixels
     * @return the {@code Builder} instance with the resolution argument added
     */
    public Builder addResolution(final int width, final int height) {
      this.arguments.add("-s");
      this.arguments.add(width + "x" + height);
      return this;
    }

    /**
     * Adds the overwrite option to allow overwriting an existing output file without prompting.
     *
     * @return the Builder instance with the overwrite option included
     */
    public Builder addOverwrite() {
      this.arguments.add("-y");
      return this;
    }

    /**
     * Adds a complex filter to the FFmpeg command being constructed.
     *
     * @param filter the complex filter expression to be added to the FFmpeg command
     * @return the current Builder instance for method chaining
     */
    public Builder addFilter(final String filter) {
      this.arguments.add("-filter_complex");
      this.arguments.add(filter);
      return this;
    }

    /**
     * Adds a custom argument to the FFmpeg command.
     *
     * @param argument the custom argument to be added to the FFmpeg command
     * @return the current instance of the Builder for method chaining
     */
    public Builder addArgument(final String argument) {
      this.arguments.add(argument);
      return this;
    }

    /**
     * Adds multiple arguments to the FFmpeg command being constructed.
     *
     * @param args an array of strings representing the arguments to be added to the FFmpeg command
     * @return the current {@code Builder} instance for method chaining
     */
    public Builder addArguments(final String... args) {
      this.arguments.addAll(Arrays.asList(args));
      return this;
    }

    /**
     * Constructs and returns a new {@code FFmpegCommand} instance using the arguments
     * that have been configured in the {@code Builder}.
     *
     * @return a fully constructed {@code FFmpegCommand} instance containing the configured arguments
     */
    public FFmpegCommand build() {
      return new FFmpegCommand(this.arguments);
    }
  }
}
