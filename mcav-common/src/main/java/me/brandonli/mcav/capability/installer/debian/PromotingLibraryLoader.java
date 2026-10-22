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
package me.brandonli.mcav.capability.installer.debian;

import com.google.common.annotations.VisibleForTesting;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

// libtheoraenc uses symbols of libtheoradec without linking it, so libtheoradec alone joins the global scope; promoting
// every library would let variants such as libexpatw shadow the symbols of libexpat.
class PromotingLibraryLoader implements Consumer<String> {

  private static final String UNLINKED_LIBRARY = "libtheoradec.so.1";

  private static final String OPEN_FUNCTION = "dlopen";

  // from dlfcn.h; RTLD_NOLOAD with RTLD_GLOBAL makes a loaded library global
  private static final int RTLD_LAZY = 0x1;
  private static final int RTLD_NOLOAD = 0x4;
  private static final int RTLD_GLOBAL = 0x100;

  // loading natives is this class's purpose
  @SuppressWarnings("restricted")
  @Override
  public void accept(final String path) {
    System.load(path);

    final boolean usedWithoutLinking = this.isUsedWithoutLinking(path);
    if (usedWithoutLinking) {
      this.promote(path);
    }
  }

  private boolean isUsedWithoutLinking(final String path) {
    final Path file = Path.of(path);
    final @Nullable Path fileName = file.getFileName();
    final String name = String.valueOf(fileName);
    return name.startsWith(UNLINKED_LIBRARY);
  }

  // Package-private so a test can see the promotion, which kills the mutant that skips it. A symbol lookup cannot,
  // because a library that an earlier test promoted stays global for the whole JVM.
  @VisibleForTesting
  void promote(final String path) {
    final NativeLibrary process = NativeLibrary.getProcess();
    final Function open = process.getFunction(OPEN_FUNCTION);
    open.invokePointer(new Object[] { path, RTLD_LAZY | RTLD_NOLOAD | RTLD_GLOBAL });
  }
}
