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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises actual bounded queue waits and interruptions without decoder scheduling or native media. */
final class PlaybackSessionTerminalTest {

  private static final class ObservedQueue extends ArrayBlockingQueue<String> {

    private static final long serialVersionUID = 1L;

    private final transient CountDownLatch timedOut = new CountDownLatch(1);
    private final transient CountDownLatch interrupted = new CountDownLatch(1);

    ObservedQueue() {
      super(1);
    }

    @Override
    public boolean offer(final String value, final long timeout, final TimeUnit unit) throws InterruptedException {
      try {
        final boolean accepted = super.offer(value, timeout, unit);
        if (!accepted) {
          this.timedOut.countDown();
        }
        return accepted;
      } catch (final InterruptedException failure) {
        this.interrupted.countDown();
        throw failure;
      }
    }
  }

  private static PlaybackSession session() {
    final VideoAttachableCallback pictures = VideoAttachableCallback.create();
    final AudioAttachableCallback sound = AudioAttachableCallback.create();
    final DimensionAttachableCallback dimensions = DimensionAttachableCallback.create();
    return new PlaybackSession(
      ScriptedFrameGrabber::of,
      null,
      pictures,
      sound,
      dimensions,
      (_, _) -> {},
      0L,
      false,
      0L,
      Long.MAX_VALUE,
      System::nanoTime
    );
  }

  @Test
  @Timeout(15)
  void terminalMarkerSurvivesAnActualOfferTimeoutAndInterruptWhileTheQueueIsFull() throws Exception {
    final PlaybackSession session = session();
    final ObservedQueue queue = new ObservedQueue();
    queue.add("last decoded frame");
    final AtomicBoolean restoredInterrupt = new AtomicBoolean();
    final Thread producer = new Thread(
      () -> {
        session.signalEnd(queue, "end of media");
        final Thread current = Thread.currentThread();
        restoredInterrupt.set(current.isInterrupted());
      },
      "terminal-marker-producer"
    );
    producer.setDaemon(true);
    try {
      producer.start();
      final boolean retried = queue.timedOut.await(5, TimeUnit.SECONDS);
      assertTrue(retried, "the full queue must cause a real timed offer to return false");
      producer.interrupt();
      final boolean interruptCaught = queue.interrupted.await(5, TimeUnit.SECONDS);
      assertTrue(interruptCaught, "interrupt the bounded queue wait, not unrelated decoder work");
      final boolean waiting = producer.isAlive();
      assertTrue(waiting, "an interrupt must not discard the terminal marker while playback remains active");
      final String frame = queue.take();
      assertEquals("last decoded frame", frame, "end signalling must not discard already queued media");
      producer.join(1_000L);
      final boolean alive = producer.isAlive();
      assertFalse(alive, "signalling finishes once the consumer frees a queue slot");
      final String terminal = queue.poll();
      final boolean keptInterrupt = restoredInterrupt.get();
      assertEquals("end of media", terminal);
      assertTrue(keptInterrupt, "the producer keeps the interrupt after delivering its terminal marker");
    } finally {
      session.stop();
      producer.interrupt();
      queue.clear();
      producer.join(2_000L);
    }
  }
}
