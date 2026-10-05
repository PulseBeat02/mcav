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
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Mcv2LatestFrame} on one thread and with an encoder thread that waits; {@code LatestFrameRace} and
 * {@code LatestFrameCloseRace} in the jcstress module race them.
 */
final class Mcv2LatestFrameTest {

  private static final long TIMEOUT_SECONDS = 10L;

  private final Mcv2LatestFrame<String> slot = new Mcv2LatestFrame<>();

  @Test
  void aNewerFrameReplacesOneNotTakenAndEveryFrameIsTakenOnce() {
    this.slot.offer("first");
    this.slot.offer("second");
    final String taken = this.slot.poll();
    final String again = this.slot.poll();

    assertEquals("second", taken);
    assertNull(again);
  }

  @Test
  void aDroppedFrameIsNotTaken() {
    this.slot.offer("dropped");
    this.slot.clear();
    assertNull(this.slot.poll());
  }

  @Test
  void anOpenSlotWaitsForTheNextFrame() throws Exception {
    this.slot.open();
    final CompletableFuture<String> taken = CompletableFuture.supplyAsync(this::takeQuietly);
    this.slot.offer("next");

    assertEquals("next", taken.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  void closingWakesAnEncoderThatWaitsWithNothing() throws Exception {
    this.slot.open();
    final CompletableFuture<String> taken = CompletableFuture.supplyAsync(this::takeQuietly);
    this.slot.close();

    assertNull(taken.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  @Test
  void aClosedSlotTakesNothingAtOnceEvenWithAFrame() throws InterruptedException {
    this.slot.offer("left over");
    final String taken = this.slot.take();
    this.slot.open();
    this.slot.offer("after opening");
    final String opened = this.slot.take();

    assertNull(taken, "a closed slot never waits and hands out nothing");
    assertEquals("after opening", opened);
  }

  @Test
  void anInterruptedTakeThrows() {
    this.slot.open();
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    assertThrows(InterruptedException.class, this.slot::take);
    assertFalse(Thread.interrupted(), "the interrupt was thrown");
  }

  private String takeQuietly() {
    try {
      return this.slot.take();
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      return null;
    }
  }
}
