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
package me.brandonli.mcav.bukkit.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the page assembler with a two-page keyframe and five real stream frames (a keyframe and four P frames),
 * reordered, repeated, corrupted, truncated or from another stream. The first input byte is reserved; after it, every four bytes are one step: what to do, which frame,
 * which of its pages, and an argument. A model of the assembler (frames pending by id, the oldest evicted beyond
 * {@link PageAssembler#MAX_PENDING}) says what every step must return: a changed, short or foreign page is always
 * rejected and changes nothing, and an intact page returns its frame, byte for byte, exactly when it completes it.
 */
@Tag("fuzz")
final class PageAssemblerFuzzTest {

  private static final long STREAM = 7;

  private static final List<byte[]> FRAMES = frames();

  private static final List<List<byte[]>> PAGES = pages(STREAM);

  private static final List<List<byte[]>> FOREIGN = pages(STREAM + 1);

  private static List<byte[]> frames() {
    final List<byte[]> frames = new ArrayList<>();
    frames.add(Mcv2Trees.twoPages());
    final List<byte[]> source = Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/proxy-default.mcs"));
    for (int index = 0; index < 5; index++) {
      final byte[] frame = source.get(index).clone();
      Mcv2Decoder.putU32(frame, 12, index + 1);
      Mcv2Decoder.putU32(frame, 16, index == 0 ? 1 : index);
      frames.add(frame);
    }
    return frames;
  }

  private static List<List<byte[]>> pages(final long stream) {
    final List<List<byte[]>> pages = new ArrayList<>();
    try {
      for (final byte[] frame : FRAMES) {
        pages.add(TransportPages.makePages(frame, stream));
      }
    } catch (final Mcv2Exception exception) {
      throw new AssertionError(exception);
    }
    return pages;
  }

  @FuzzTest(maxDuration = "30s")
  void assemblesExactlyTheFramesThatArrive(final byte[] data) throws Mcv2Exception {
    if (data.length < 1) {
      return;
    }
    final int symbolBits = 6;
    final PageAssembler assembler = new PageAssembler(STREAM, symbolBits);
    final Map<Long, Set<Integer>> pending = new LinkedHashMap<>();
    for (int step = 1; step + 3 < data.length; step += 4) {
      final int action = data[step] & 0xFF;
      final int index = (data[step + 1] & 0xFF) % FRAMES.size();
      final List<byte[]> frame = PAGES.get(index);
      final int number = (data[step + 2] & 0xFF) % frame.size();
      final int argument = data[step + 3] & 0xFF;
      final byte[] page = frame.get(number);
      switch (action % 4) {
        case 0 -> {
          final long id = index;
          if (!pending.containsKey(id) && pending.size() >= PageAssembler.MAX_PENDING) {
            final Iterator<Long> oldest = pending.keySet().iterator();
            oldest.next();
            oldest.remove();
          }
          final Set<Integer> numbers = pending.computeIfAbsent(id, _ -> new HashSet<>());
          numbers.add(number);
          final byte[] assembled = assembler.push(page);
          if (numbers.size() == frame.size()) {
            assertArrayEquals(FRAMES.get(index), assembled);
            pending.remove(id);
          } else {
            assertNull(assembled);
          }
        }
        case 1 -> {
          // one symbol changed: a burst of at most eight bits, which the CRC always detects if nothing else does
          final byte[] changed = page.clone();
          changed[(argument * 97) % changed.length] ^= (byte) ((action >>> 2) | 1);
          assertThrows(Mcv2Exception.class, () -> assembler.push(changed));
        }
        case 2 -> {
          final byte[] cut = Arrays.copyOf(page, page.length - 1 - (argument % 16));
          assertThrows(Mcv2Exception.class, () -> assembler.push(cut));
        }
        default -> {
          final byte[] foreign = FOREIGN.get(index).get(number);
          assertThrows(Mcv2Exception.class, () -> assembler.push(foreign));
        }
      }
      assertEquals(pending.size(), assembler.getPendingCount());
    }
  }
}
