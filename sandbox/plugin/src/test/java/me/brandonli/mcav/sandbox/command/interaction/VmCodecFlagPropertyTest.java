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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import me.brandonli.mcav.sandbox.utils.MapCodec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of the {@code --codec} at the end of {@code /mcav vm create}'s QEMU options: it is split off whatever
 * options come before it, and whatever a player types is either left to QEMU's option parser whole, or split into
 * options that were a prefix of it and a codec.
 */
final class VmCodecFlagPropertyTest {

  private static final String SEED = "20260928";

  @Provide
  Arbitrary<String> options() {
    final Arbitrary<String> option = Arbitraries.of(
      "-m 2048M",
      "-smp 2",
      "-cdrom \"alpine linux.iso\"",
      "-snapshot",
      "-vga std",
      "-name test"
    );
    return option
      .list()
      .ofMaxSize(5)
      .map(list -> String.join(" ", list));
  }

  @Provide
  Arbitrary<String> gaps() {
    return Arbitraries.strings().withChars(" \t").ofMinLength(1).ofMaxLength(3);
  }

  @Property(seed = SEED)
  void splitsOffTheCodecAtTheEnd(
    @ForAll("options") final String options,
    @ForAll final MapCodec codec,
    @ForAll final boolean upper,
    @ForAll("gaps") final String gap
  ) {
    final String name = upper ? codec.name() : codec.name().toLowerCase(Locale.ROOT);
    final VirtualizeCommand.CodecSplit split = VirtualizeCommand.splitCodec(options + gap + "--codec" + gap + name + gap);
    assertEquals(new VirtualizeCommand.CodecSplit(options.strip(), codec), split);
  }

  @Property(seed = SEED)
  void leavesOptionsWithoutATrailingCodecWhole(@ForAll("options") final String options) {
    assertEquals(new VirtualizeCommand.CodecSplit(options, null), VirtualizeCommand.splitCodec(options));
  }

  @Property(seed = SEED)
  void splitsWhateverIsTypedIntoAPrefixAndACodecOrNothing(@ForAll final String typed) {
    final VirtualizeCommand.CodecSplit split = VirtualizeCommand.splitCodec(typed);
    if (split == null) {
      assertTrue(typed.contains("--codec"), typed);
      return;
    }
    if (split.codec() == null) {
      assertEquals(typed, split.options());
      return;
    }
    assertTrue(typed.startsWith(split.options()), typed);
    assertTrue(typed.strip().toLowerCase(Locale.ROOT).endsWith(split.codec().name().toLowerCase(Locale.ROOT)), typed);
  }
}
