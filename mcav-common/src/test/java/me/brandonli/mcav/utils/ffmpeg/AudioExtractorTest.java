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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import me.brandonli.mcav.utils.runtime.ProcessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AudioExtractor} with the bundled FFmpeg. The tests change {@code user.home}, so they run in
 * isolation.
 */
@Isolated
final class AudioExtractorTest {

  private static final float SAMPLE_RATE = 8000;

  @TempDir
  private Path directory;

  private String previousHome;

  /**
   * Extracts the bundled FFmpeg into the real JavaCPP cache before any test moves the home directory, because the
   * native libraries stay locked for the lifetime of the JVM and would keep the temporary directory from being
   * deleted.
   */
  @BeforeAll
  static void extractFFmpeg() {
    final Path executable = FFmpegExecutableProvider.getFFmpegPath();
    final boolean exists = Files.isRegularFile(executable);
    assertTrue(exists, "the bundled FFmpeg is available");
  }

  @BeforeEach
  void useTemporaryHome() {
    this.previousHome = System.getProperty("user.home");
    final String temporaryHome = this.directory.toString();
    System.setProperty("user.home", temporaryHome);
  }

  @AfterEach
  void restoreHome() {
    System.setProperty("user.home", this.previousHome);
  }

  private Path writeTone() throws IOException {
    final int sampleCount = (int) SAMPLE_RATE;
    final byte[] samples = new byte[sampleCount * 2];
    for (int index = 0; index < sampleCount; index++) {
      final double angle = (2 * Math.PI * 440 * index) / SAMPLE_RATE;
      final short sample = (short) (Math.sin(angle) * 12_000);
      samples[index * 2] = (byte) sample;
      samples[index * 2 + 1] = (byte) (sample >> 8);
    }
    final AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    final Path wave = this.directory.resolve("tone.wav");
    final File waveFile = wave.toFile();
    try (
      final ByteArrayInputStream input = new ByteArrayInputStream(samples);
      final AudioInputStream stream = new AudioInputStream(input, format, sampleCount)
    ) {
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, waveFile);
    }
    return wave;
  }

  @Test
  void extractsTheAudioIntoANewOggVorbisFileInTheCache() throws IOException {
    final Path wave = this.writeTone();
    final FileSource source = FileSource.path(wave);
    final Path first = AudioExtractor.extractOggVorbis(source);
    final Path second = AudioExtractor.extractOggVorbis(source);
    final Path cache = IOUtils.getCachedFolder();
    final Path parent = first.getParent();
    final Path fileName = first.getFileName();
    final String name = fileName.toString();
    final byte[] bytes = Files.readAllBytes(first);
    final byte[] magic = Arrays.copyOf(bytes, 4);
    final String magicText = new String(magic, StandardCharsets.US_ASCII);
    final String content = new String(bytes, StandardCharsets.ISO_8859_1);
    final boolean mentionsVorbis = content.contains("vorbis");
    final boolean endsWithOgg = name.endsWith(".ogg");
    assertEquals(cache, parent);
    assertTrue(endsWithOgg);
    assertEquals("OggS", magicText);
    assertTrue(mentionsVorbis, "the stream is encoded with Vorbis");
    assertNotEquals(first, second, "every extraction gets its own file");
  }

  @Test
  void reportsMediaThatFFmpegCannotRead() {
    final Path missing = this.directory.resolve("missing.wav");
    final FileSource source = FileSource.path(missing);
    assertThrows(ProcessException.class, () -> AudioExtractor.extractOggVorbis(source));
    assertThrows(NullPointerException.class, () -> AudioExtractor.extractOggVorbis(null));
  }

  @Test
  void removesOnlyThePartialOutputWhenExtractionFails() throws IOException {
    this.checkFailedExtraction(false, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void keepsTheExtractionFailureWhenDeletingThePartialFileFails(final boolean sameFailure) throws IOException {
    this.checkFailedExtraction(true, sameFailure);
  }

  private void checkFailedExtraction(final boolean cleanupFails, final boolean sameFailure) throws IOException {
    final Path cache = IOUtils.getCachedFolder();
    final Path existing = Files.writeString(cache.resolve("keep.ogg"), "existing audio");
    final AtomicReference<Path> partial = new AtomicReference<>();
    final FFmpegCommand command = mock(FFmpegCommand.class);
    final CommandTask task = mock(CommandTask.class);
    final IOException failure = new IOException("output failed after writing bytes");
    final IOException cleanupFailure = sameFailure ? failure : new IOException("partial output could not be deleted");
    when(command.createTask()).thenReturn(task);
    doAnswer(_ -> {
      Files.writeString(partial.get(), "partial audio");
      throw failure;
    })
      .when(task)
      .runChecked();
    try (
      final MockedStatic<FFmpegTemplates> templates = Mockito.mockStatic(FFmpegTemplates.class);
      final MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)
    ) {
      templates
        .when(() -> FFmpegTemplates.extractOggVorbis(anyString(), anyString()))
        .thenAnswer(invocation -> {
          final String output = invocation.getArgument(1);
          final Path outputPath = Path.of(output);
          partial.set(outputPath);
          if (cleanupFails) {
            files.when(() -> Files.deleteIfExists(outputPath)).thenThrow(cleanupFailure);
          }
          return command;
        });
      final FileSource source = FileSource.path(this.directory.resolve("input.wav"));
      final IOException thrown = assertThrows(IOException.class, () -> AudioExtractor.extractOggVorbis(source));
      assertSame(failure, thrown);
      assertEquals(cleanupFails, Files.exists(partial.get()), "only a cleanup failure can leave the unowned partial output");
      final Throwable[] expectedFailures = cleanupFails && !sameFailure ? new Throwable[] { cleanupFailure } : new Throwable[0];
      assertArrayEquals(expectedFailures, failure.getSuppressed());
      assertEquals("existing audio", Files.readString(existing));
      if (!cleanupFails) {
        try (final Stream<Path> cacheFiles = Files.list(cache)) {
          assertEquals(List.of(existing), cacheFiles.toList());
        }
      }
    }
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(AudioExtractor.class);
  }
}
