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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real QEMU guests and listens to them through the audio pipeline of the player. The guests are boot sectors
 * whose sources lie next to them: {@code beep.asm} plays a 1000 Hz tone on the PC speaker, and {@code toggle.asm}
 * switches that tone and a red screen on and off together at irregular times, so the sound can be matched with the
 * picture. Skipped where QEMU for x86-64 is not installed; the matching times real events, so it only runs with
 * {@code -Pmcav.syncMeasurement=true}.
 */
final class VMSoundTest {

  private static final int TONE_HERTZ = 1000;
  // 5 ms of samples, which hold exactly 5 periods of the tone
  private static final int WINDOW_FRAMES = AudioFilter.SAMPLE_RATE / 200;

  @TempDir
  private Path directory;

  private static void assumeQemuInstalled() {
    final Optional<Path> qemu = new ExecutableFinder().find(VMPlayer.Architecture.X86_64.getCommand());
    assumeTrue(qemu.isPresent(), "QEMU for x86-64 is not installed");
  }

  private Path bootSector(final String name) throws IOException {
    final Path image = this.directory.resolve(name);
    try (InputStream resource = Objects.requireNonNull(VMSoundTest.class.getResourceAsStream(name), name)) {
      Files.copy(resource, image);
    }
    return image;
  }

  private static VMConfiguration floppy(final Path image) {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(16);
    configuration.option("drive", "file=" + image + ",format=raw,if=floppy");
    configuration.boot("a");
    return configuration;
  }

  private static void waitUntil(final String description, final long timeoutSeconds, final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "timed out waiting for " + description);
      try {
        Thread.sleep(20L);
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
    }
  }

  /**
   * Measures how strong the tone is in a window of the left channel, compared with everything in it.
   *
   * @param left    the samples of the left channel
   * @param start   the first sample of the window
   * @param length  the number of samples
   * @return the share of the energy at the tone, from 0 to 1, or 0 for silence
   */
  static double toneShare(final short[] left, final int start, final int length) {
    final double omega = (2 * Math.PI * TONE_HERTZ) / AudioFilter.SAMPLE_RATE;
    final double coefficient = 2 * Math.cos(omega);
    double previous = 0;
    double beforePrevious = 0;
    double energy = 0;
    for (int index = start; index < start + length; index++) {
      final double sample = left[index];
      energy += sample * sample;
      final double current = sample + coefficient * previous - beforePrevious;
      beforePrevious = previous;
      previous = current;
    }
    if (energy < length * 100.0 * 100.0) {
      return 0;
    }
    final double power = previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious;
    // a sine of the tone gives power = energy * length / 2
    return Math.min(1, power / ((energy * length) / 2));
  }

  private static short[] leftChannel(final byte[] pcm) {
    final ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    final short[] left = new short[pcm.length / AudioFilter.FRAME_SIZE];
    for (int index = 0; index < left.length; index++) {
      left[index] = samples.getShort(index * AudioFilter.FRAME_SIZE);
    }
    return left;
  }

  @Test
  void theToneOfTheGuestsPcSpeakerReachesTheAudioPipeline() throws IOException {
    assumeQemuInstalled();
    final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    final VMPlayer player = VMPlayer.create();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(
      AudioPipelineStep.of((samples, metadata) -> {
        assertEquals(AudioFilter.SAMPLE_RATE, metadata.getAudioSampleRate());
        final byte[] copy = new byte[samples.remaining()];
        samples.get(copy);
        synchronized (pcm) {
          pcm.writeBytes(copy);
        }
        return true;
      })
    );
    try {
      assertTrue(player.start(VMSettings.of(320, 200, 10), VMPlayer.Architecture.X86_64, floppy(this.bootSector("beep.img"))));
      final int twoSeconds = 2 * AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE;
      waitUntil("two seconds of sound", 60, () -> {
        synchronized (pcm) {
          return pcm.size() >= twoSeconds;
        }
      });
    } finally {
      player.release();
    }
    final byte[] captured;
    synchronized (pcm) {
      captured = pcm.toByteArray();
    }
    final short[] left = leftChannel(captured);
    // the last second, after the guest started the tone
    final int start = left.length - AudioFilter.SAMPLE_RATE;
    int crossings = 0;
    for (int index = start + 1; index < left.length; index++) {
      if ((left[index - 1] < 0) != (left[index] < 0)) {
        crossings++;
      }
    }
    final double frequency = crossings / 2.0;
    System.out.printf(Locale.ROOT, "captured %d bytes, zero crossings say %.1f Hz%n", captured.length, frequency);
    assertTrue(Math.abs(frequency - TONE_HERTZ) < 20, "the tone has " + frequency + " Hz");
    int toneWindows = 0;
    final int windows = AudioFilter.SAMPLE_RATE / WINDOW_FRAMES;
    for (int window = 0; window < windows; window++) {
      if (toneShare(left, start + window * WINDOW_FRAMES, WINDOW_FRAMES) > 0.5) {
        toneWindows++;
      }
    }
    assertTrue(toneWindows > (windows * 9) / 10, toneWindows + " of " + windows + " windows hold the tone");
  }

  @Test
  void theSoundOfTheGuestKeepsUpWithItsPicture() throws IOException {
    assumeTrue(Boolean.getBoolean("mcav.syncMeasurement"), "times real events; run with -Pmcav.syncMeasurement=true on a quiet machine");
    assumeQemuInstalled();
    final List<long[]> sound = new CopyOnWriteArrayList<>();
    final List<long[]> picture = new CopyOnWriteArrayList<>();
    final VMPlayer player = VMPlayer.create();
    player
      .getAudioAttachableCallback()
      .attach(
        AudioPipelineStep.of((samples, metadata) -> {
          final long arrival = System.nanoTime();
          final byte[] copy = new byte[samples.remaining()];
          samples.get(copy);
          final short[] left = leftChannel(copy);
          final int windows = left.length / WINDOW_FRAMES;
          for (int window = 0; window < windows; window++) {
            final boolean tone = toneShare(left, window * WINDOW_FRAMES, WINDOW_FRAMES) > 0.5;
            // the samples of a chunk played before it arrived, the last one just now
            final long end = (long) (window + 1) * WINDOW_FRAMES;
            final long before = TimeUnit.SECONDS.toNanos(left.length - end) / AudioFilter.SAMPLE_RATE;
            sound.add(new long[] { arrival - before, tone ? 1 : 0 });
          }
          return true;
        })
      );
    final VideoAttachableCallback video = player.getVideoAttachableCallback();
    video.attach(
      VideoPipelineStep.of((image, metadata) -> {
        final ByteBuffer pixels = image.getData();
        final int center = pixels.position() + ((image.getHeight() / 2) * image.getWidth() + image.getWidth() / 2) * 3;
        final int blue = pixels.get(center) & 0xFF;
        final int red = pixels.get(center + 2) & 0xFF;
        picture.add(new long[] { System.nanoTime(), red > 128 && blue < 64 ? 1 : 0 });
        return false;
      })
    );
    try {
      assertTrue(player.start(VMSettings.of(320, 200, 60), VMPlayer.Architecture.X86_64, floppy(this.bootSector("toggle.img"))));
      waitUntil("20 seconds of sound", 90, () -> sound.size() >= 20 * 200);
    } finally {
      player.release();
    }
    final List<Long> soundOnsets = onsets(sound);
    final List<Long> pictureOnsets = onsets(picture);
    final List<Double> offsets = new ArrayList<>();
    for (final long shown : pictureOnsets) {
      long nearest = Long.MAX_VALUE;
      for (final long heard : soundOnsets) {
        if (Math.abs(heard - shown) < Math.abs(nearest)) {
          nearest = heard - shown;
        }
      }
      if (Math.abs(nearest) < TimeUnit.MILLISECONDS.toNanos(150)) {
        offsets.add(nearest / 1e6);
      }
    }
    assertTrue(offsets.size() >= 10, "matched " + offsets.size() + " of " + pictureOnsets.size() + " changes of the picture");
    final List<Double> sorted = new ArrayList<>(offsets);
    Collections.sort(sorted);
    final long within = offsets.stream().filter(offset -> offset >= -40 && offset <= 80).count();
    final int half = offsets.size() / 2;
    final double firstHalf = median(offsets.subList(0, half));
    final double secondHalf = median(offsets.subList(half, offsets.size()));
    System.out.printf(
      Locale.ROOT,
      "A/V sync: %d changes matched of %d, sound minus picture: median %.1f ms, p5 %.1f ms, p95 %.1f ms, %d within [-40, +80] ms; first half %.1f ms, second half %.1f ms%n",
      offsets.size(),
      pictureOnsets.size(),
      median(sorted),
      sorted.get(sorted.size() / 20),
      sorted.get((sorted.size() * 19) / 20),
      within,
      firstHalf,
      secondHalf
    );
    // ITU-R BT.1359: sound may lead the picture by 90 ms and lag it by 185 ms before viewers find it unacceptable
    final long acceptable = offsets.stream().filter(offset -> offset >= -90 && offset <= 185).count();
    System.out.printf(Locale.ROOT, "A/V sync: %d of %d within ITU-R BT.1359 acceptability [-90, +185] ms%n", acceptable, offsets.size());
    // the picture reaches the pipeline late now and then, when QEMU refreshes its VNC display late or the host is busy;
    // the sound is held so that the two arrive together in the middle, and they must not drift apart
    assertTrue(Math.abs(median(sorted)) <= 40, "the sound and the picture arrive together in the middle");
    assertTrue(acceptable >= Math.ceil(offsets.size() * 0.9), acceptable + " of " + offsets.size() + " changes are in sync");
    assertTrue(Math.abs(secondHalf - firstHalf) < 25, "the sound does not drift from the picture");
  }

  private static double median(final List<Double> values) {
    final List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  /**
   * Finds the times at which a signal switches on: a sample that is on after one that is off, or after a gap. QEMU
   * sends no sound at all while the speaker is off, so the sound has gaps where the picture has dark frames.
   *
   * @param samples pairs of a time in nanoseconds and a state, 1 for on
   * @return the times the signal switched on
   */
  static List<Long> onsets(final List<long[]> samples) {
    final List<long[]> ordered = new ArrayList<>(samples);
    ordered.sort((first, second) -> Long.compare(first[0], second[0]));
    final long gap = TimeUnit.MILLISECONDS.toNanos(60);
    final List<Long> onsets = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      final long[] current = ordered.get(index);
      if (current[1] != 1) {
        continue;
      }
      final boolean first = index == 0;
      final boolean afterOff = !first && ordered.get(index - 1)[1] == 0;
      final boolean afterGap = !first && current[0] - ordered.get(index - 1)[0] > gap;
      if (first || afterOff || afterGap) {
        onsets.add(current[0]);
      }
    }
    return onsets;
  }
}
