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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Pipelining is the same encoder called in another order: frame N verified on another thread while frame N+1 is
 * searched gives, byte for byte, the stream one frame at a time gives, for any picture size, pan, live profile and
 * keyframe interval.
 */
final class PipelinedEncoderPropertyTest {

  private static final String SEED = "20260927";

  private static final List<EncoderSettings> PROFILES = List.of(
    EncoderSettings.LIVE,
    EncoderSettings.LIVE_FAST,
    EncoderSettings.LIVE.withReference(EncoderSettings.ReferencePolicy.LAST_KEYFRAME),
    EncoderSettings.LIVE_FAST.withReference(EncoderSettings.ReferencePolicy.LAST_KEYFRAME)
  );

  @Property(seed = SEED, tries = 40)
  boolean pipelinesToTheSameStream(
    @ForAll @IntRange(min = 0, max = 3) final int profile,
    @ForAll @IntRange(min = 1, max = 4) final int keyInterval,
    @ForAll @IntRange(min = 8, max = 120) final int width,
    @ForAll @IntRange(min = 8, max = 90) final int height,
    @ForAll @IntRange(min = 2, max = 6) final int frames,
    @ForAll @IntRange(min = -5, max = 5) final int dx
  ) throws InterruptedException, ExecutionException {
    final EncoderSettings settings = PROFILES.get(profile).withKeyInterval(keyInterval);
    final List<byte[]> one = PipelinedEncoderTest.sequential(settings, width, height, frames, dx);
    final List<byte[]> two = PipelinedEncoderTest.pipelined(settings, width, height, frames, dx);
    for (int i = 0; i < one.size(); i++) {
      if (!Arrays.equals(one.get(i), two.get(i))) {
        return false;
      }
    }
    return one.size() == two.size();
  }
}
