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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

// jcstress races these in LatestFrameRace and LatestFrameCloseRace
final class Mcv2LatestFrameTest {

  private static final long TIMEOUT_SECONDS = 10L;

  private static final long POLL_MILLISECONDS = 5L;

  private final Mcv2LatestFrame<String> slot = new Mcv2LatestFrame<>();

  @Test
  void shouldReplaceAFrameNotTakenWithANewerOneAndTakeEveryFrameOnce() {
    this.slot.offer("first");
    this.slot.offer("second");
    final String taken = this.slot.poll();
    final String again = this.slot.poll();

    assertEquals("second", taken);
    assertNull(again);
  }

  @Test
  void shouldNotTakeADroppedFrame() {
    this.slot.offer("dropped");
    this.slot.clear();
    final @Nullable String taken = this.slot.poll();
    assertNull(taken);
  }

  @Test
  void shouldWaitForTheNextFrameWhenTheSlotIsOpen() throws Exception {
    this.slot.open();
    final CompletableFuture<String> taken = this.startWaitingTaker();
    this.slot.offer("next");

    final String next = taken.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals("next", next);
  }

  @Test
  void shouldWakeAnEncoderThatWaitsWithNothingWhenClosing() throws Exception {
    this.slot.open();
    final CompletableFuture<String> taken = this.startWaitingTaker();
    this.slot.close();

    final @Nullable String woken = taken.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNull(woken);
  }

  @Test
  void shouldTakeNothingAtOnceFromAClosedSlotEvenWithAFrame() throws InterruptedException {
    this.slot.offer("left over");
    final String taken = this.slot.take();
    this.slot.open();
    this.slot.offer("after opening");
    final String opened = this.slot.take();

    assertNull(taken, "a closed slot never waits and hands out nothing");
    assertEquals("after opening", opened);
  }

  @Test
  void shouldThrowWhenATakeIsInterrupted() {
    this.slot.open();
    final Thread current = Thread.currentThread();
    current.interrupt();
    assertThrows(InterruptedException.class, this.slot::take);

    final boolean interrupted = Thread.interrupted();
    assertFalse(interrupted, "the interrupt was thrown");
  }

  private CompletableFuture<String> startWaitingTaker() throws InterruptedException {
    final CompletableFuture<String> taken = new CompletableFuture<>();
    final Thread taker = Thread.ofPlatform()
      .daemon()
      .start(() -> taken.complete(this.takeQuietly()));
    this.awaitWaiting(taker);

    return taken;
  }

  private String takeQuietly() {
    try {
      return this.slot.take();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      return null;
    }
  }

  private void awaitWaiting(final Thread thread) throws InterruptedException {
    final long timeout = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    final long deadline = System.nanoTime() + timeout;
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(POLL_MILLISECONDS);
    }

    final Thread.State state = thread.getState();
    assertEquals(Thread.State.WAITING, state, "the taker waits for a frame");
  }
}
