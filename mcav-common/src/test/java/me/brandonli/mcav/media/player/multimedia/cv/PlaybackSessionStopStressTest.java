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
package me.brandonli.mcav.media.player.multimedia.cv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.bytedeco.javacv.Frame;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Stops playback sessions at many points of their life. This is the stress half of the race tests of PlaybackSession:
// jcstress runs its actors millions of times and needs them to finish in microseconds, which a session that starts up
// to four threads and paces its frames by the clock does not, so EndMarkerRace in mcav-jcstress covers only the one
// step of stopping that fits, the end marker replacing a queue a decoder refills. Every round plays a scripted source,
// sometimes with a separate audio source and sometimes with a decoder that gets stuck for a moment, and stops it at a
// point drawn from a fixed seed: before the threads start, right after the start, while paused, after some frames, as
// the media ends, from its own filter, from two threads at once, and from its filter and from outside at once. The seed
// fixes the rounds, not the interleavings, which is the point of running many. Wherever the stop lands, once
// PlaybackSession.stop() returns to a caller outside the session, every thread of the session has ended and every
// grabber it opened is closed; once the session ended, every picture its pipeline saw is released; and nothing is ever
// reported, because nothing in the scripts fails.
final class PlaybackSessionStopStressTest {

  private static final long SEED = 20_260_925L;
  private static final int ROUNDS = 150;
  private static final long FRAME_MICROSECONDS = 1_000L;
  private static final long TIMEOUT_MILLISECONDS = 10_000L;
  private static final Duration TIMEOUT = Duration.ofMillis(TIMEOUT_MILLISECONDS);
  private static final String DECODER_PREFIX = "mcav-decode-";
  private static final String RENDERER_PREFIX = "mcav-render-";
  private static final int WAITING_DECODER_STOPS = 300;
  private static final int WAITING_DECODER_FRAMES = 21;
  private static final long WAITING_DECODER_FRAME_MICROSECONDS = 33_333L;
  private static final long WAITING_DECODER_SETTLE_NANOSECONDS = 200_000L;

  private static final int FRAME_WIDTH = 4;
  private static final int FRAME_HEIGHT = 2;

  // Loads the OpenCV natives before the first round, because the first image buffer of the JVM loads them on the
  // decoding thread, where the loading takes seconds.
  @BeforeAll
  static void loadTheNativeLibrariesAheadOfTheRounds() {
    try (final ImageBuffer warmUp = ImageBuffer.bytes(new byte[3], 1, 1)) {
      final int width = warmUp.getWidth();
      assertEquals(1, width, "the natives are loaded and work");
    }
  }

  @Test
  void shouldEndTheWholeSessionOnEveryStopWhereverItLands() throws Exception {
    final SplittableRandom random = new SplittableRandom(SEED);
    final StopPoint[] points = StopPoint.values();
    for (int number = 0; number < ROUNDS; number++) {
      final StopPoint point = points[random.nextInt(points.length)];
      final Round round = new Round(number, point, random);
      round.play();
    }
  }

  // Stopping drains the video queue, which wakes a decoder that waits for room in it. When the wake-up comes before the
  // decoder notices its interrupt, the put completes after all, and it could complete after the renderer drained the
  // queue for the last time, so nobody released the picture of that frame. Found by the rounds above; which comes first
  // is up to the scheduler, about one stop in twenty-five on the machine it was found on, so the stop is repeated until
  // missing the case every time would take a miracle.
  @Test
  void shouldNotLeaveThePictureOfADecoderWaitingForRoomBehindOnAnyStop() throws Exception {
    for (int stop = 0; stop < WAITING_DECODER_STOPS; stop++) {
      final List<Object> script = new ArrayList<>();
      for (int index = 0; index < WAITING_DECODER_FRAMES; index++) {
        final Frame picture = ScriptedFrameGrabber.video(index * WAITING_DECODER_FRAME_MICROSECONDS);
        script.add(picture);
      }
      final ScriptedFrameGrabber grabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, script);
      final VideoAttachableCallback videoCallback = VideoAttachableCallback.create();
      final VideoPipelineStep blocking = VideoPipelineStep.of(PlaybackSessionStopStressTest::blockUntilInterrupted);
      videoCallback.attach(blocking);
      final PlaybackSession session = new PlaybackSession(
        () -> grabber,
        null,
        videoCallback,
        AudioAttachableCallback.create(),
        DimensionAttachableCallback.create(),
        (_, _) -> {},
        0L,
        StartState.PLAYING,
        PlaybackSession.AUDIO_LEAD_NANOSECONDS,
        Long.MAX_VALUE,
        System::nanoTime
      );
      session.start();
      final int remainingSteps = WAITING_DECODER_FRAMES - 6;
      Polling.awaitCondition("the decoder waits for room", TIMEOUT, () -> grabber.getRemainingSteps() == remainingSteps);
      LockSupport.parkNanos(WAITING_DECODER_SETTLE_NANOSECONDS);
      final long waitingPictures = session.getQueuedPictureCount();
      assertTrue(waitingPictures > 0, "the decoder waits because the queue is full");

      session.stop();
      final long queuedPictures = session.getQueuedPictureCount();
      final int number = stop;
      assertEquals(0L, queuedPictures, () -> "stop " + number + " left a picture in the queue, where nobody releases it");
    }
  }

  // A video filter that blocks until its thread is interrupted, keeping the interrupt, so the renderer holds one frame
  // while the decoder fills the queue.
  private static boolean blockUntilInterrupted(final ImageBuffer picture, final OriginalVideoMetadata metadata) {
    final CountDownLatch never = new CountDownLatch(1);
    try {
      never.await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    return true;
  }

  private static Set<Thread> liveSessionThreads() {
    final Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    final Set<Thread> sessionThreads = new HashSet<>();
    for (final Thread thread : stacks.keySet()) {
      final String name = thread.getName();
      final boolean ofASession = name.startsWith(DECODER_PREFIX) || name.startsWith(RENDERER_PREFIX);
      if (ofASession && thread.isAlive()) {
        sessionThreads.add(thread);
      }
    }

    return sessionThreads;
  }

  private enum StopPoint {
    BEFORE_THE_THREADS,
    RIGHT_AFTER_THE_START,
    WHILE_PAUSED,
    AFTER_SOME_FRAMES,
    AS_THE_MEDIA_ENDS,
    FROM_ITS_OWN_FILTER,
    FROM_TWO_THREADS,
    FROM_ITS_FILTER_AND_OUTSIDE,
  }

  private static final class Round {

    private final String description;
    private final StopPoint point;
    private final int stopAfterFrames;
    private final long waitBeforeStopNanoseconds;
    private final ScriptedFrameGrabber videoGrabber;
    private final @Nullable ScriptedFrameGrabber audioGrabber;
    private final AtomicBoolean audioOpened;
    private final Set<ImageBuffer> pictures;
    private final AtomicInteger frames;
    private final AtomicReference<@Nullable Thread> videoRenderer;
    private final CountDownLatch ownStopReturned;
    private final List<String> reports;
    private final Set<Thread> threadsBefore;
    private final PlaybackSession session;

    Round(final int number, final StopPoint point, final SplittableRandom random) {
      final int frameCount = random.nextInt(1, 16);
      final boolean hasSeparateAudio = random.nextBoolean();
      final boolean stuckDecoder = random.nextInt(4) == 0;
      this.point = point;
      this.stopAfterFrames = random.nextInt(1, frameCount + 1);
      this.waitBeforeStopNanoseconds = random.nextLong(0L, 2_000_000L);
      this.description = String.format(
        Locale.ROOT,
        "round %d of seed %d: stop %s, %d frames, stop after %d frames or %d ns, %s audio%s",
        number,
        SEED,
        point,
        frameCount,
        this.stopAfterFrames,
        this.waitBeforeStopNanoseconds,
        hasSeparateAudio ? "separate" : "interleaved",
        stuckDecoder ? ", decoder stuck for a moment" : ""
      );
      final List<Object> video = new ArrayList<>();
      final List<Object> audio = new ArrayList<>();
      for (int index = 0; index < frameCount; index++) {
        final long timestamp = index * FRAME_MICROSECONDS;
        final Frame picture = ScriptedFrameGrabber.video(timestamp);
        final Frame sound = ScriptedFrameGrabber.audio(timestamp);
        video.add(picture);
        if (hasSeparateAudio) {
          audio.add(sound);
        } else {
          video.add(sound);
        }
        if (stuckDecoder && random.nextInt(3) == 0) {
          final ScriptedFrameGrabber.Delay delay = new ScriptedFrameGrabber.Delay(random.nextInt(1, 4));
          video.add(delay);
        }
      }

      this.videoGrabber = new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, video);
      this.audioGrabber = hasSeparateAudio ? new ScriptedFrameGrabber(FRAME_WIDTH, FRAME_HEIGHT, audio) : null;
      this.audioOpened = new AtomicBoolean();
      this.pictures = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
      this.frames = new AtomicInteger();
      this.videoRenderer = new AtomicReference<>();
      this.ownStopReturned = new CountDownLatch(1);
      this.reports = Collections.synchronizedList(new ArrayList<>());
      this.threadsBefore = liveSessionThreads();
      final StartState startState = point == StopPoint.WHILE_PAUSED ? StartState.PAUSED : StartState.PLAYING;
      this.session = this.createSession(startState);
    }

    private PlaybackSession createSession(final StartState startState) {
      final VideoAttachableCallback videoCallback = VideoAttachableCallback.create();
      final AudioAttachableCallback audioCallback = AudioAttachableCallback.create();
      final DimensionAttachableCallback dimensionCallback = DimensionAttachableCallback.create();
      final VideoPipelineStep videoStep = VideoPipelineStep.of(this::showPicture);
      final AudioPipelineStep audioStep = AudioPipelineStep.of((_, _) -> true);
      videoCallback.attach(videoStep);
      audioCallback.attach(audioStep);
      final ScriptedFrameGrabber audio = this.audioGrabber;
      final PlaybackSession.GrabberFactory audioFactory = audio == null ? null : () -> this.openAudio(audio);
      return new PlaybackSession(
        () -> this.videoGrabber,
        audioFactory,
        videoCallback,
        audioCallback,
        dimensionCallback,
        (message, _) -> this.reports.add(message),
        0L,
        startState,
        PlaybackSession.AUDIO_LEAD_NANOSECONDS,
        Long.MAX_VALUE,
        System::nanoTime
      );
    }

    private boolean showPicture(final ImageBuffer picture, final OriginalVideoMetadata metadata) {
      this.pictures.add(picture);
      final Thread currentThread = Thread.currentThread();
      this.videoRenderer.set(currentThread);
      final int frame = this.frames.incrementAndGet();
      final boolean stopsItself = this.point == StopPoint.FROM_ITS_OWN_FILTER || this.point == StopPoint.FROM_ITS_FILTER_AND_OUTSIDE;
      if (stopsItself && frame == this.stopAfterFrames) {
        this.session.stop();
        this.ownStopReturned.countDown();
      }

      return true;
    }

    private ScriptedFrameGrabber openAudio(final ScriptedFrameGrabber audio) {
      this.audioOpened.set(true);

      return audio;
    }

    void play() throws Exception {
      if (this.point == StopPoint.BEFORE_THE_THREADS) {
        this.session.open();
        this.session.stop();
        this.assertStoppedForItsCaller(this.description);
        this.assertEnded();
        return;
      }

      this.session.start();
      switch (this.point) {
        case RIGHT_AFTER_THE_START, WHILE_PAUSED -> {
          LockSupport.parkNanos(this.waitBeforeStopNanoseconds);
          this.stopFromOutside();
        }
        case AFTER_SOME_FRAMES -> {
          this.awaitFramesOrEnd();
          this.stopFromOutside();
        }
        case AS_THE_MEDIA_ENDS -> {
          Polling.awaitCondition(
            this.description + ", the decoder read the whole script",
            TIMEOUT,
            () -> this.videoGrabber.getRemainingSteps() == 0
          );
          this.stopFromOutside();
        }
        case FROM_ITS_OWN_FILTER -> this.awaitOwnStop();
        case FROM_TWO_THREADS -> {
          this.awaitFramesOrEnd();
          this.stopFromTwoThreads();
        }
        case FROM_ITS_FILTER_AND_OUTSIDE -> this.stopFromOutsideAsTheFilterDoes();
        case BEFORE_THE_THREADS -> {
          final String message = "Point %s is not a stop after the start!".formatted(this.point);
          throw new IllegalStateException(message);
        }
      }

      this.assertEnded();
    }

    // Waits until the pipeline saw the frames after which the round stops, or the media ended before that because
    // frames were due before the pipeline could show them all.
    private void awaitFramesOrEnd() throws InterruptedException {
      Polling.awaitCondition(
        this.description + ", the frames were shown or the media ended",
        TIMEOUT,
        () -> this.frames.get() >= this.stopAfterFrames || !this.session.isActive()
      );
    }

    private void stopFromOutside() {
      this.session.stop();
      this.assertStoppedForItsCaller(this.description);
    }

    // Stops the session from two threads at once; each must find the session stopped when its stop returns.
    private void stopFromTwoThreads() throws InterruptedException {
      final CountDownLatch gate = new CountDownLatch(1);
      final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
      final List<Thread> stoppers = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        final String caller = this.description + ", stopping thread " + index;
        final Thread stopper = new Thread(() -> this.stopAfter(gate, caller, failures), "stress-stop-" + index);
        stoppers.add(stopper);
        stopper.start();
      }

      gate.countDown();
      for (final Thread stopper : stoppers) {
        stopper.join(TIMEOUT_MILLISECONDS);
        final boolean alive = stopper.isAlive();
        assertFalse(alive, () -> this.description + ", a stopping thread hangs");
      }

      assertEquals(List.of(), failures, this.description);
    }

    private void stopAfter(final CountDownLatch gate, final String caller, final List<Throwable> failures) {
      try {
        gate.await();
        this.session.stop();
        this.assertStoppedForItsCaller(caller);
      } catch (final InterruptedException | AssertionError exception) {
        failures.add(exception);
      }
    }

    private void stopFromOutsideAsTheFilterDoes() throws InterruptedException {
      final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLISECONDS);
      while (this.session.isActive()) {
        final long now = System.nanoTime();
        final boolean beforeDeadline = now - deadline < 0;
        assertTrue(beforeDeadline, () -> this.description + ", the session never stopped itself or ended");
        Thread.onSpinWait();
      }

      this.stopFromOutside();
      this.awaitOwnStop();
    }

    private void awaitOwnStop() throws InterruptedException {
      final boolean returned = this.ownStopReturned.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      assertTrue(returned, () -> this.description + ", the filter did not return from stopping its own session");

      final Thread renderer = this.videoRenderer.get();
      if (renderer != null) {
        renderer.join(TIMEOUT_MILLISECONDS);
      }

      this.assertStoppedForItsCaller(this.description);
    }

    private void assertStoppedForItsCaller(final String caller) {
      final Set<Thread> alive = liveSessionThreads();
      alive.removeAll(this.threadsBefore);
      final boolean videoClosed = this.videoGrabber.isClosed();
      final ScriptedFrameGrabber audio = this.audioGrabber;
      final boolean audioClosed = audio == null || !this.audioOpened.get() || audio.isClosed();
      assertEquals(Set.of(), alive, () -> caller + ": threads of the session still run after the stop returned");
      assertTrue(videoClosed, () -> caller + ": the video grabber is still open after the stop returned");
      assertTrue(audioClosed, () -> caller + ": the audio grabber is still open after the stop returned");
    }

    private void assertEnded() {
      final List<ImageBuffer> seen;
      synchronized (this.pictures) {
        seen = new ArrayList<>(this.pictures);
      }

      for (final ImageBuffer picture : seen) {
        assertThrows(IllegalStateException.class, picture::getWidth, () -> this.description + ": a picture was not released");
      }

      final long queuedPictures = this.session.getQueuedPictureCount();
      final boolean active = this.session.isActive();
      assertEquals(0L, queuedPictures, () -> this.description + ": pictures were left in the queue, where nobody releases them");
      assertFalse(active, this.description);
      assertEquals(List.of(), this.reports, this.description);
    }
  }
}
