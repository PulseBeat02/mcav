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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Pipelining is the same encoder called in another order: frame N verified on another thread while frame N+1 is
 * searched gives, byte for byte, the stream one frame at a time gives, for any picture size, pan, live profile and
 * keyframe interval.
 */
final class MCV2PipelinePropertyTest {

  private static final String SEED = "20260927";

  private static final List<Settings> PROFILES = List.of(Settings.DEFAULT, Settings.FAST, Settings.ADAPTIVE, Settings.FAST.withLambda(110));

  @Property(seed = SEED, tries = 40)
  boolean pipelinesToTheSameStream(
    @ForAll @IntRange(min = 0, max = 3) final int profile,
    @ForAll @IntRange(min = 1, max = 4) final int keyInterval,
    @ForAll @IntRange(min = 8, max = 120) final int width,
    @ForAll @IntRange(min = 8, max = 90) final int height,
    @ForAll @IntRange(min = 2, max = 6) final int frames,
    @ForAll @IntRange(min = -5, max = 5) final int panPerFrame
  ) throws InterruptedException, ExecutionException {
    final Settings preset = PROFILES.get(profile);
    final Settings settings = new Settings(preset.lambda(), keyInterval, preset.fast(), preset.adaptive());
    final List<byte[]> one = MCV2PipelineTest.sequential(settings, width, height, frames, panPerFrame);
    final List<byte[]> two = MCV2PipelineTest.pipelined(settings, width, height, frames, panPerFrame);
    for (int frameIndex = 0; frameIndex < one.size(); frameIndex++) {
      if (!Arrays.equals(one.get(frameIndex), two.get(frameIndex))) {
        return false;
      }
    }
    return one.size() == two.size();
  }
}
