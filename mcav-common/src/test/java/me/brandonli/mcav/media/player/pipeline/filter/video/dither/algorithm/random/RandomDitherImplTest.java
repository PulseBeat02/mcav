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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link RandomDitherImpl} hands out its random number generators to threads.
 */
final class RandomDitherImplTest {

  private static final DitherPalette BLACK_WHITE = DitherPalette.colors(0x000000, 0xFFFFFF);

  private static RandomNumberProvider providerOfAnotherThread(final RandomDitherImpl algorithm) throws InterruptedException {
    final AtomicReference<RandomNumberProvider> provider = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      final RandomNumberProvider own = algorithm.getRandomNumberProvider();
      provider.set(own);
    });
    thread.start();
    thread.join(5_000L);
    final boolean stillRunning = thread.isAlive();
    if (stillRunning) {
      thread.interrupt();
    }
    assertFalse(stillRunning, "the worker completed its generator lookup");
    final RandomNumberProvider result = provider.get();
    assertNotNull(result, "the worker returned a generator");
    return result;
  }

  @Test
  void givesEveryThreadItsOwnGenerator() throws Exception {
    final RandomDitherImpl shared = new RandomDitherImpl(BLACK_WHITE, RandomDither.NORMAL_WEIGHT);
    final RandomNumberProvider mine = shared.getRandomNumberProvider();
    final RandomNumberProvider mineAgain = shared.getRandomNumberProvider();
    final RandomNumberProvider other = providerOfAnotherThread(shared);
    assertSame(mine, mineAgain, "a thread keeps its generator");
    assertNotSame(mine, other, "the generator is not thread-safe, so threads that share the algorithm never share it");
  }

  @Test
  void handsAnExplicitProviderToEveryThread() throws Exception {
    final RandomNumberProvider provider = mock(RandomNumberProvider.class);
    final RandomDitherImpl explicit = new RandomDitherImpl(BLACK_WHITE, 1, provider);
    final RandomNumberProvider mine = explicit.getRandomNumberProvider();
    final RandomNumberProvider other = providerOfAnotherThread(explicit);
    assertSame(provider, mine);
    assertSame(provider, other);
  }
}
