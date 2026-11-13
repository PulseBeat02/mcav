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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

// the umask belongs to the whole process, so no other test may create files meanwhile
@Isolated
final class ArchiveExtractorUmaskTest {

  private static final String UMASK_FUNCTION = "umask";

  private static final int OWNER_ONLY_UMASK = 077;

  private static final String OUTER_FOLDER = "outer";
  private static final String NESTED_FOLDER = "outer/nested";

  private static final String FOLDER_PERMISSIONS = "rwxr-xr-x";

  @TempDir
  private Path folder;

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldWidenNewFoldersPastAnOwnerOnlyUmask() throws Throwable {
    final int previousUmask = this.replaceUmask(OWNER_ONLY_UMASK);
    try {
      ArchiveExtractor.createFolders(this.folder.resolve(NESTED_FOLDER));
    } finally {
      this.replaceUmask(previousUmask);
    }

    final Set<PosixFilePermission> expected = PosixFilePermissions.fromString(FOLDER_PERMISSIONS);
    final Set<PosixFilePermission> outer = Files.getPosixFilePermissions(this.folder.resolve(OUTER_FOLDER));
    assertEquals(expected, outer);

    final Set<PosixFilePermission> nested = Files.getPosixFilePermissions(this.folder.resolve(NESTED_FOLDER));
    assertEquals(expected, nested);
  }

  // umask is called through the restricted native linker; the tests run with native access enabled
  @SuppressWarnings("restricted")
  private int replaceUmask(final int umask) throws Throwable {
    final Linker linker = Linker.nativeLinker();
    final MethodHandle function = linker.downcallHandle(
      linker.defaultLookup().findOrThrow(UMASK_FUNCTION),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    );
    return (int) function.invokeExact(umask);
  }
}
