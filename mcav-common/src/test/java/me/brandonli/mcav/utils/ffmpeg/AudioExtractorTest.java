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
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import me.brandonli.mcav.utils.runtime.ProcessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

// Tests AudioExtractor with the bundled FFmpeg. The tests change user.home, so they run in isolation.
@Isolated
final class AudioExtractorTest {

  private static final float SAMPLE_RATE = 8000;

  private static final String TONE_FILE_NAME = "tone.wav";
  private static final String MISSING_FILE_NAME = "missing.wav";
  private static final String INPUT_FILE_NAME = "input.wav";
  private static final String KEPT_FILE_NAME = "keep.ogg";
  private static final String OGG_EXTENSION = ".ogg";

  private static final String USER_HOME_PROPERTY = "user.home";

  @TempDir
  private Path directory;

  private String previousHome;

  // Extracts the bundled FFmpeg into the real JavaCPP cache before any test moves the home directory, because the
  // native libraries stay locked for the lifetime of the JVM and would keep the temporary directory from being deleted.
  @BeforeAll
  static void extractFfmpeg() {
    final Path executable = FfmpegExecutableProvider.getFfmpegPath();
    final boolean exists = Files.isRegularFile(executable);
    assertTrue(exists, "the bundled FFmpeg is available");
  }

  @BeforeEach
  void useTemporaryHome() {
    this.previousHome = System.getProperty(USER_HOME_PROPERTY);
    final String temporaryHome = this.directory.toString();
    System.setProperty(USER_HOME_PROPERTY, temporaryHome);
  }

  @AfterEach
  void restoreHome() {
    System.setProperty(USER_HOME_PROPERTY, this.previousHome);
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
    final Path wave = this.directory.resolve(TONE_FILE_NAME);
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
  void shouldExtractTheAudioIntoANewOggVorbisFileInTheCache() throws IOException {
    final Path wave = this.writeTone();
    final FileSource source = FileSource.path(wave);
    final Path first = AudioExtractor.extractOggVorbis(source);
    final Path second = AudioExtractor.extractOggVorbis(source);
    final Path cache = IoUtils.getCachedFolder();
    final Path parent = first.getParent();
    final Path fileName = first.getFileName();
    final String name = fileName.toString();
    final byte[] bytes = Files.readAllBytes(first);
    final byte[] magic = Arrays.copyOf(bytes, 4);
    final String magicText = new String(magic, StandardCharsets.US_ASCII);
    final String content = new String(bytes, StandardCharsets.ISO_8859_1);
    final boolean mentionsVorbis = content.contains("vorbis");
    final boolean endsWithOgg = name.endsWith(OGG_EXTENSION);
    assertEquals(cache, parent);
    assertTrue(endsWithOgg);
    assertEquals("OggS", magicText);
    assertTrue(mentionsVorbis, "the stream is encoded with Vorbis");
    assertNotEquals(first, second, "every extraction gets its own file");
  }

  @Test
  void shouldReportMediaThatFfmpegCannotRead() {
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final FileSource source = FileSource.path(missing);
    assertThrows(ProcessException.class, () -> AudioExtractor.extractOggVorbis(source));
  }

  @Test
  void shouldRejectANullSource() {
    assertThrows(NullPointerException.class, () -> AudioExtractor.extractOggVorbis(null));
  }

  @Test
  void shouldRemoveOnlyThePartialOutputWhenExtractionFails() throws IOException {
    this.checkFailedExtraction(Cleanup.SUCCEEDS);
  }

  @ParameterizedTest
  @EnumSource(value = Cleanup.class, mode = EnumSource.Mode.EXCLUDE, names = "SUCCEEDS")
  void shouldKeepTheExtractionFailureWhenDeletingThePartialFileFails(final Cleanup cleanup) throws IOException {
    this.checkFailedExtraction(cleanup);
  }

  private void checkFailedExtraction(final Cleanup cleanup) throws IOException {
    final boolean cleanupFails = cleanup != Cleanup.SUCCEEDS;
    final boolean sameFailure = cleanup == Cleanup.FAILS_WITH_THE_EXTRACTION_EXCEPTION;
    final Path cache = IoUtils.getCachedFolder();
    final Path existing = Files.writeString(cache.resolve(KEPT_FILE_NAME), "existing audio");
    final AtomicReference<Path> partial = new AtomicReference<>();
    final FfmpegCommand command = mock(FfmpegCommand.class);
    final CommandTask task = mock(CommandTask.class);
    final MCAVException failure = new MCAVException("output failed after writing bytes!");
    final Exception cleanupFailure = sameFailure ? failure : new IOException("partial output could not be deleted!");
    when(command.createTask()).thenReturn(task);
    doAnswer(_ -> this.writePartialOutputAndFail(partial, failure))
      .when(task)
      .runChecked();
    try (
      final MockedStatic<FfmpegTemplates> templates = Mockito.mockStatic(FfmpegTemplates.class);
      final MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)
    ) {
      templates
        .when(() -> FfmpegTemplates.extractOggVorbis(anyString(), anyString()))
        .thenAnswer(invocation -> this.recordPartialOutput(invocation, partial, files, cleanup, cleanupFailure, command));
      final FileSource source = FileSource.path(this.directory.resolve(INPUT_FILE_NAME));
      final MCAVException thrown = assertThrows(MCAVException.class, () -> AudioExtractor.extractOggVorbis(source));
      assertSame(failure, thrown);
      final boolean partialLeft = Files.exists(partial.get());
      assertEquals(cleanupFails, partialLeft, "only a cleanup failure can leave the unowned partial output");
      final Throwable[] expectedFailures = cleanupFails && !sameFailure ? new Throwable[] { cleanupFailure } : new Throwable[0];
      final Throwable[] suppressedFailures = failure.getSuppressed();
      assertArrayEquals(expectedFailures, suppressedFailures);
      final String existingText = Files.readString(existing);
      assertEquals("existing audio", existingText);
      if (!cleanupFails) {
        this.assertCacheHoldsOnly(cache, existing);
      }
    }
  }

  private Object writePartialOutputAndFail(final AtomicReference<Path> partial, final MCAVException failure) throws IOException {
    Files.writeString(partial.get(), "partial audio");

    throw failure;
  }

  private FfmpegCommand recordPartialOutput(
    final InvocationOnMock invocation,
    final AtomicReference<Path> partial,
    final MockedStatic<Files> files,
    final Cleanup cleanup,
    final Exception cleanupFailure,
    final FfmpegCommand command
  ) {
    final String output = invocation.getArgument(1);
    final Path outputPath = Path.of(output);
    partial.set(outputPath);

    if (cleanup != Cleanup.SUCCEEDS) {
      files.when(() -> Files.deleteIfExists(outputPath)).thenThrow(cleanupFailure);
    }

    return command;
  }

  private void assertCacheHoldsOnly(final Path cache, final Path existing) throws IOException {
    try (final Stream<Path> cacheFiles = Files.list(cache)) {
      final List<Path> cachedFiles = cacheFiles.toList();
      final List<Path> expected = List.of(existing);
      assertEquals(expected, cachedFiles);
    }
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(AudioExtractor.class);
  }

  private enum Cleanup {
    SUCCEEDS,
    FAILS_WITH_ITS_OWN_EXCEPTION,
    FAILS_WITH_THE_EXTRACTION_EXCEPTION,
  }
}
