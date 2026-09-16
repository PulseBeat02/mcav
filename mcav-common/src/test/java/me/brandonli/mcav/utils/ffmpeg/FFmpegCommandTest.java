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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FFmpegCommand}, its builder and {@link FFmpegExecutableProvider}.
 */
final class FFmpegCommandTest {

  @Test
  void builderAddsEveryArgumentInOrder() {
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    final FFmpegCommand.Builder returned = builder.addInput("in.mp4");
    builder.addVideoCodec("libx264");
    builder.addAudioCodec("aac");
    builder.addBitrate("1M");
    builder.addAudioBitrate("128k");
    builder.addFramerate(30);
    builder.addResolution(640, 360);
    builder.addFilter("scale=320:-1");
    builder.addArgument("-an");
    builder.addArguments("-t", "5");
    builder.addOverwrite();
    builder.addOutput("out.mp4");
    final FFmpegCommand command = builder.build();
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      "-i",
      "in.mp4",
      "-c:v",
      "libx264",
      "-c:a",
      "aac",
      "-b:v",
      "1M",
      "-b:a",
      "128k",
      "-r",
      "30",
      "-s",
      "640x360",
      "-filter_complex",
      "scale=320:-1",
      "-an",
      "-t",
      "5",
      "-y",
      "out.mp4"
    );
    assertSame(builder, returned);
    assertEquals(expected, arguments);
  }

  @Test
  void everyBuilderMethodReturnsTheSameBuilder() {
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    final FFmpegCommand.Builder afterInput = builder.addInput("in.mp4");
    final FFmpegCommand.Builder afterOutput = builder.addOutput("out.mp4");
    final FFmpegCommand.Builder afterVideoCodec = builder.addVideoCodec("libx264");
    final FFmpegCommand.Builder afterAudioCodec = builder.addAudioCodec("aac");
    final FFmpegCommand.Builder afterBitrate = builder.addBitrate("1M");
    final FFmpegCommand.Builder afterAudioBitrate = builder.addAudioBitrate("128k");
    final FFmpegCommand.Builder afterFramerate = builder.addFramerate(30);
    final FFmpegCommand.Builder afterResolution = builder.addResolution(640, 360);
    final FFmpegCommand.Builder afterOverwrite = builder.addOverwrite();
    final FFmpegCommand.Builder afterFilter = builder.addFilter("scale=320:-1");
    final FFmpegCommand.Builder afterArgument = builder.addArgument("-an");
    final FFmpegCommand.Builder afterArguments = builder.addArguments("-t", "5");
    assertSame(builder, afterInput);
    assertSame(builder, afterOutput);
    assertSame(builder, afterVideoCodec);
    assertSame(builder, afterAudioCodec);
    assertSame(builder, afterBitrate);
    assertSame(builder, afterAudioBitrate);
    assertSame(builder, afterFramerate);
    assertSame(builder, afterResolution);
    assertSame(builder, afterOverwrite);
    assertSame(builder, afterFilter);
    assertSame(builder, afterArgument);
    assertSame(builder, afterArguments);
  }

  @Test
  void commandStartsWithTheBundledExecutable() throws IOException {
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addArgument("-version");
    final FFmpegCommand command = builder.build();
    final Path executable = command.getExecutable();
    final String executablePath = executable.toString();
    final String[] commandArray = command.toCommandArray();
    final String text = command.toString();
    final CommandTask task = command.createTask();
    final List<String> taskCommand = task.getCommand();
    final int exitCode = task.run();
    final String output = task.getOutput();
    final List<String> expectedTaskCommand = List.of(commandArray);
    final boolean printsTheVersion = output.startsWith("ffmpeg version");
    assertArrayEquals(new String[] { executablePath, "-version" }, commandArray);
    assertEquals(executablePath + " -version", text);
    assertEquals(expectedTaskCommand, taskCommand);
    assertEquals(0, exitCode);
    assertTrue(printsTheVersion, output);
  }

  @Test
  void commandsAreImmutable() {
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    builder.addArgument("-version");
    final FFmpegCommand command = builder.build();
    builder.addArgument("-h");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-version");
    assertEquals(expected, arguments);
    assertThrows(UnsupportedOperationException.class, () -> arguments.add("-x"));
  }

  @Test
  void builderRejectsInvalidArguments() {
    final FFmpegCommand.Builder builder = FFmpegCommand.builder();
    assertThrows(IllegalArgumentException.class, () -> builder.addFramerate(0));
    assertThrows(IllegalArgumentException.class, () -> builder.addResolution(0, 10));
    assertThrows(IllegalArgumentException.class, () -> builder.addResolution(10, 0));
    assertThrows(NullPointerException.class, () -> builder.addInput(null));
    assertThrows(NullPointerException.class, () -> builder.addOutput(null));
    assertThrows(NullPointerException.class, () -> builder.addVideoCodec(null));
    assertThrows(NullPointerException.class, () -> builder.addAudioCodec(null));
    assertThrows(NullPointerException.class, () -> builder.addBitrate(null));
    assertThrows(NullPointerException.class, () -> builder.addAudioBitrate(null));
    assertThrows(NullPointerException.class, () -> builder.addFilter(null));
    assertThrows(NullPointerException.class, () -> builder.addArgument(null));
    assertThrows(NullPointerException.class, () -> builder.addArguments((String[]) null));
    assertThrows(NullPointerException.class, () -> builder.addArguments("-t", null));
  }

  @Test
  void providesAnExistingExecutableOnce() throws ReflectiveOperationException {
    final Path first = FFmpegExecutableProvider.getFFmpegPath();
    final Path second = FFmpegExecutableProvider.getFFmpegPath();
    final boolean exists = Files.isRegularFile(first);
    assertTrue(exists);
    assertSame(first, second);
    UtilityClassAssertions.assertNotInstantiable(FFmpegExecutableProvider.class);
  }

  @Test
  void holderCannotBeInstantiated() throws ReflectiveOperationException {
    final Class<?>[] nestedClasses = FFmpegExecutableProvider.class.getDeclaredClasses();
    assertEquals(1, nestedClasses.length);
    UtilityClassAssertions.assertNotInstantiable(nestedClasses[0]);
  }
}
