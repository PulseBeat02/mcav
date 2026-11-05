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
package me.brandonli.mcav.capability.installer.vlc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import uk.co.caprica.vlcj.binding.lib.LibVlc;

// The second half of VlcReleaseTest, run in a JVM of its own because libvlc can be loaded only once per JVM and other
// tests load the system's VLC: loads the VLC installed in the folder it is given, never the system's, and plays a video
// through it. Arguments: the installation folder given to VlcInstaller.create(Path), and a video with sound. It prints
// PASSED and exits with 0 once frames and sound arrived.
final class VlcPlaybackProbe {

  static final String PASSED = "VLC PLAYBACK PASSED";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int MINIMUM_FRAMES = 20;
  private static final long MINIMUM_AUDIO_BYTES = 96_000L;

  private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(3);

  private static final int SUCCESS_EXIT_CODE = 0;
  private static final int FAILURE_EXIT_CODE = 1;
  private static final int STUCK_EXIT_CODE = 3;

  private VlcPlaybackProbe() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void main(final String[] arguments) throws IOException, InterruptedException {
    final Path folder = Path.of(arguments[0]);
    final Path video = Path.of(arguments[1]);
    startWatchdog();
    final VlcInstaller installer = VlcInstaller.create(folder);
    final VlcInstallationKit kit = new VlcInstallationKit(installer, DownloadedVlc.createDiscovery(), new VlcLoadState());
    final Optional<Path> loaded = kit.start();
    final String version = LibVlc.libvlc_get_version();
    final Path loadedFrom = loaded.orElseThrow();
    System.out.println("libvlc %s loaded from %s".formatted(version, loadedFrom));

    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong audioBytes = new AtomicLong();
    System.out.println("step: creating the player");
    final VideoPlayerMultiplexer player = VideoPlayer.vlc();
    player.setExceptionHandler((message, error) -> System.out.println("player error: %s %s".formatted(message, error)));
    player
      .getVideoAttachableCallback()
      .attach(VideoPipelineStep.of((final ImageBuffer image, final OriginalVideoMetadata metadata) -> countFrame(frames)));
    player
      .getAudioAttachableCallback()
      .attach(AudioPipelineStep.of((final ByteBuffer samples, final OriginalAudioMetadata metadata) -> countSamples(audioBytes, samples)));

    final Source source = FileSource.path(video);
    System.out.println("step: starting the video");
    final boolean started = player.start(source);
    System.out.println("step: started %s".formatted(started));
    final boolean played =
      started && Polling.pollUntil(TIMEOUT, () -> frames.get() >= MINIMUM_FRAMES && audioBytes.get() >= MINIMUM_AUDIO_BYTES);

    final int framesBeforeRelease = frames.get();
    final long audioBytesBeforeRelease = audioBytes.get();
    System.out.println("step: releasing, frames %s, audio bytes %s".formatted(framesBeforeRelease, audioBytesBeforeRelease));
    final boolean released = player.release();
    final int frameCount = frames.get();
    final long audioByteCount = audioBytes.get();
    System.out.println("started %s, frames %s, audio bytes %s, released %s".formatted(started, frameCount, audioByteCount, released));

    if (!played || !released) {
      System.exit(FAILURE_EXIT_CODE);
    }

    System.out.println(PASSED);
    System.exit(SUCCESS_EXIT_CODE);
  }

  private static void startWatchdog() {
    final Thread watchdog = Thread.ofPlatform().daemon().name("vlc-probe-watchdog").unstarted(VlcPlaybackProbe::reportIfStuck);
    watchdog.start();
  }

  private static void reportIfStuck() {
    try {
      Thread.sleep(STUCK_TIMEOUT.toMillis());
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      return;
    }

    final long stuckMinutes = STUCK_TIMEOUT.toMinutes();
    System.out.println("probe stuck after %s minutes; its threads:".formatted(stuckMinutes));
    for (final Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      final Thread thread = entry.getKey();
      final String name = thread.getName();
      final Thread.State state = thread.getState();
      System.out.println("thread %s %s".formatted(name, state));
      for (final StackTraceElement element : entry.getValue()) {
        System.out.println("    at %s".formatted(element));
      }
    }

    System.exit(STUCK_EXIT_CODE);
  }

  private static boolean countFrame(final AtomicInteger frames) {
    frames.incrementAndGet();

    return false;
  }

  private static boolean countSamples(final AtomicLong audioBytes, final ByteBuffer samples) {
    audioBytes.addAndGet(samples.remaining());

    return false;
  }
}
