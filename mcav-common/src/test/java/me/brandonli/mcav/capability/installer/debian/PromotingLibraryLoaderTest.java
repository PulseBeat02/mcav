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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.jna.NativeLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PromotingLibraryLoaderTest {

  private static final String JAVA_HOME_PROPERTY = "java.home";
  private static final String LIBRARY_FOLDER = "lib";

  private static final String LINUX_ONLY_MESSAGE = "The loader uses the dlopen flags of Linux!";

  private static final String MISSING_LIBRARY = "libmissing.so";

  // copies of JDK libraries whose symbols the JVM keeps local stand in for Debian libraries
  private static final String JIMAGE_LIBRARY = "jimage";
  private static final String JIMAGE_SYMBOL = "JIMAGE_Open";
  private static final String THEORA_DECODER_FILE = "libtheoradec.so.1.1.4";

  private static final String VERIFY_LIBRARY = "verify";
  private static final String VERIFY_SYMBOL = "VerifyClassForMajorVersion";

  private PromotingLibraryLoader loader;

  @BeforeEach
  void setUp() {
    this.loader = new PromotingLibraryLoader();
  }

  @Test
  void shouldPutTheSymbolsOfLibtheoradecInTheGlobalScope(@TempDir final Path folder) throws IOException {
    assumeTrue(OsUtils.getOs() == Os.LINUX, LINUX_ONLY_MESSAGE);

    final Path copy = folder.resolve(THEORA_DECODER_FILE);
    this.copyJdkLibrary(JIMAGE_LIBRARY, copy);
    this.loader.accept(copy.toString());

    final NativeLibrary process = NativeLibrary.getProcess();
    assertDoesNotThrow(() -> process.getFunction(JIMAGE_SYMBOL));
  }

  @Test
  void shouldKeepTheSymbolsOfOtherLibrariesLocal(@TempDir final Path folder) throws IOException {
    assumeTrue(OsUtils.getOs() == Os.LINUX, LINUX_ONLY_MESSAGE);

    final String fileName = System.mapLibraryName(VERIFY_LIBRARY);
    final Path copy = folder.resolve(fileName);
    this.copyJdkLibrary(VERIFY_LIBRARY, copy);
    this.loader.accept(copy.toString());

    final NativeLibrary process = NativeLibrary.getProcess();
    assertThrows(UnsatisfiedLinkError.class, () -> process.getFunction(VERIFY_SYMBOL));
  }

  @Test
  void shouldFailForALibraryThatDoesNotLoad(@TempDir final Path folder) {
    final Path missing = folder.resolve(MISSING_LIBRARY);
    final String path = missing.toString();

    assertThrows(UnsatisfiedLinkError.class, () -> this.loader.accept(path));
  }

  private void copyJdkLibrary(final String library, final Path copy) throws IOException {
    final String javaHome = System.getProperty(JAVA_HOME_PROPERTY);
    final String fileName = System.mapLibraryName(library);
    final Path original = Path.of(javaHome, LIBRARY_FOLDER, fileName);
    Files.copy(original, copy);
  }
}
