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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

// Installs the pinned package lists from the real archive, checking every hash. The download of about 290 MiB
// runs only with -Pmcav.networkTests=true; only this machine's architecture is loaded.
final class DebianLibraryReleaseTest {

  private static final String VLC_RESOURCE = "mcav/common/installers/debian-vlc.json";
  private static final String GTK2_RESOURCE = "mcav/common/installers/debian-gtk2.json";
  private static final String FFMPEG_RESOURCE = "mcav/common/installers/debian-ffmpeg.json";

  private static final Platform ARM64 = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
  private static final Platform X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);

  private static final String LOAD_ORDER_FILE = "load-order.txt";
  private static final String GTK2_LIBRARY = "libgtk-x11-2.0.so.0";
  private static final String SOUND_LIBRARY = "libasound.so.2";
  private static final String VLC_CORE_LIBRARY = "/libvlccore.so.9";
  private static final String VLC_LIBRARY = "/libvlc.so.5";
  private static final String TRIPLET_SUFFIX = "-linux-gnu";
  private static final char PATH_SEPARATOR = '/';

  @TempDir
  private Path temporary;

  @BeforeAll
  static void requireNetworkTests() {
    final boolean enabled = Boolean.getBoolean("mcav.networkTests");
    Assumptions.assumeTrue(enabled, "Network tests run only with -Pmcav.networkTests=true!");
  }

  @ParameterizedTest
  @EnumSource(value = Arch.class, names = { "X86", "ARM" })
  void shouldInstallVlcWithItsPluginsAfterItsDependencies(final Arch architecture) throws IOException {
    final Platform platform = Platform.ofPlatform(Os.LINUX, architecture, Bits.BITS_64);
    final DebianLibraryInstaller installer = this.createInstaller(VLC_RESOURCE, platform, this::refuse);
    final Path libraryDirectory = installer.download(FileMode.EXECUTABLE);

    final boolean libvlc = Files.exists(libraryDirectory.resolve("libvlc.so.5"));
    final boolean plugins = Files.isDirectory(libraryDirectory.resolve("vlc/plugins"));
    assertTrue(libvlc);
    assertTrue(plugins);

    final List<String> loadOrder = this.readLoadOrder();
    final int core = this.indexOf(loadOrder, VLC_CORE_LIBRARY);
    final int vlc = this.indexOf(loadOrder, VLC_LIBRARY);
    assertTrue(core >= 0 && core < vlc, "Libvlccore must load before libvlc!");
  }

  @Test
  void shouldInstallAndLoadVlcOnThisMachine() throws IOException {
    this.assumeX86Machine();

    final List<String> loaded = new ArrayList<>();
    final DebianLibraryInstaller installer = this.createInstaller(VLC_RESOURCE, X86_64, library -> this.loadAndRecord(library, loaded));
    installer.download(FileMode.EXECUTABLE);
    installer.loadLibraries();

    final List<String> loadOrder = this.readLoadOrder();
    final long expected = loadOrder.stream().filter(this::isInLibraryFolder).count();
    final long loadedFromLibraryFolders = loaded.stream().filter(this::isInLibraryFolder).count();
    assertEquals(expected, loadedFromLibraryFolders, "Every library of the library folders must load!");

    final int core = this.indexOf(loaded, VLC_CORE_LIBRARY);
    final int vlc = this.indexOf(loaded, VLC_LIBRARY);
    assertTrue(core >= 0 && vlc >= 0, "Libvlccore and libvlc must load!");
  }

  @ParameterizedTest
  @CsvSource({ GTK2_RESOURCE + "," + GTK2_LIBRARY, FFMPEG_RESOURCE + "," + SOUND_LIBRARY })
  void shouldInstallTheLibrariesForArm64(final String resource, final String library) {
    final DebianLibraryInstaller installer = this.createInstaller(resource, ARM64, this::refuse);
    final Path libraryDirectory = installer.download(FileMode.EXECUTABLE);

    final boolean installed = Files.exists(libraryDirectory.resolve(library));
    assertTrue(installed);
  }

  @ParameterizedTest
  @CsvSource({ GTK2_RESOURCE + "," + GTK2_LIBRARY, FFMPEG_RESOURCE + "," + SOUND_LIBRARY })
  void shouldInstallAndLoadEveryLibraryOnThisMachine(final String resource, final String library) throws IOException {
    this.assumeX86Machine();

    final List<String> loaded = new ArrayList<>();
    final DebianLibraryInstaller installer = this.createInstaller(resource, X86_64, path -> this.loadAndRecord(path, loaded));
    installer.download(FileMode.EXECUTABLE);
    installer.loadLibraries();

    final List<String> loadOrder = this.readLoadOrder();
    assertEquals(loadOrder.size(), loaded.size(), "Every library must load!");

    final int index = this.indexOf(loaded, library);
    assertTrue(index >= 0, library);
  }

  private void assumeX86Machine() {
    final Platform current = Platform.getCurrentPlatform();
    Assumptions.assumeTrue(current.equals(X86_64), "Only a 64-bit x86 Linux machine loads the x86-64 libraries!");
  }

  private DebianLibraryInstaller createInstaller(final String resource, final Platform platform, final Consumer<String> loader) {
    final Download[] downloads = IoUtils.readDownloadsFromJsonResource(resource);
    final Download[] platformDownloads = Arrays.stream(downloads)
      .filter(download -> download.getPlatform().equals(platform))
      .toArray(Download[]::new);
    final Path folder = this.temporary.resolve("installation");
    return new DebianLibraryInstaller(folder, "debian-release", platformDownloads, platform, loader);
  }

  private List<String> readLoadOrder() throws IOException {
    final Path folder = this.temporary.resolve("installation");
    return Files.readAllLines(folder.resolve(LOAD_ORDER_FILE));
  }

  private int indexOf(final List<String> libraries, final String library) {
    for (int index = 0; index < libraries.size(); index++) {
      if (libraries.get(index).contains(library)) {
        return index;
      }
    }

    return -1;
  }

  private boolean isInLibraryFolder(final String library) {
    final int folderEnd = library.lastIndexOf(PATH_SEPARATOR);
    final String folder = library.substring(0, folderEnd);
    return folder.endsWith(TRIPLET_SUFFIX);
  }

  private void refuse(final String library) {
    throw new UnsatisfiedLinkError("Libraries of another architecture are not loaded!");
  }

  // the libraries of this machine's architecture really load, which proves that the dependency order holds
  private void loadAndRecord(final String library, final List<String> loaded) {
    final PromotingLibraryLoader loader = new PromotingLibraryLoader();
    loader.accept(library);
    loaded.add(library);
  }
}
