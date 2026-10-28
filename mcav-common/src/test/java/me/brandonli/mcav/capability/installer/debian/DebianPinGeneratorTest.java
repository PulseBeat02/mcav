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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class DebianPinGeneratorTest {

  private static final URI MAIN_SNAPSHOT = URI.create("https://snapshot.debian.org/archive/debian/20261017T000000Z/");
  private static final URI SECURITY_SNAPSHOT = URI.create("https://snapshot.debian.org/archive/debian-security/20261017T000000Z/");

  private static final String INDEX = """
  Package: libroot1
  Architecture: arm64
  Pre-Depends: libc6 (>= 2.36)
  Depends: libmiddle2 (>= 1.0), libalternative1 | libunused1, libvirtual, libroot-data
  Filename: pool/main/r/root/libroot1_1.0_arm64.deb
  SHA256: 1111
  Description: a root library
   whose description: spans lines

  Package: libmiddle2
  Architecture: arm64
  Depends: libleaf0:any, libroot1
  Filename: pool/main/m/middle/libmiddle2_2.0_arm64.deb
  SHA256: 2222

  Package: libleaf0
  Version: 0.1-1
  Architecture: arm64
  Depends: libgcc-s1, libstdc++6
  Filename: pool/main/l/leaf/libleaf0_0.1_arm64.deb
  SHA256: 3333

  Package: libalternative1
  Version: 1-1
  Architecture: arm64
  Filename: pool/main/a/alternative/libalternative1_1_arm64.deb
  SHA256: 4444

  Package: libunused1
  Version: 1
  Architecture: arm64
  Filename: pool/main/u/unused/libunused1_1_arm64.deb
  SHA256: 5555

  Package: libprovider3
  Architecture: arm64
  Provides: libvirtual (= 3)
  Filename: pool/main/p/provider/libprovider3_3_arm64.deb
  SHA256: 6666

  Package: libroot-data
  Architecture: all
  Depends: libunused1
  Filename: pool/main/r/root/libroot-data_1.0_all.deb
  SHA256: 7777

  Package: libc6
  Architecture: arm64
  Filename: pool/main/g/glibc/libc6_2.36_arm64.deb
  SHA256: 8888
  """;

  private static final String SECURITY_INDEX = """
  Package: libleaf0
  Version: 0.1-1+deb12u1
  Architecture: arm64
  Filename: pool/updates/main/l/leaf/libleaf0_0.1-1+deb12u1_arm64.deb
  SHA256: 9999

  Package: libalternative1
  Version: 1-1~deb12u1
  Architecture: arm64
  Filename: pool/updates/main/a/alternative/libalternative1_1-1~deb12u1_arm64.deb
  SHA256: aaaa

  Package: libunused1
  Version: 1
  Architecture: arm64
  Filename: pool/updates/main/u/unused/libunused1_1_arm64.deb
  SHA256: bbbb
  """;

  private static final List<DebianPinGenerator.Index> MAIN = List.of(new DebianPinGenerator.Index(MAIN_SNAPSHOT, INDEX));
  private static final List<DebianPinGenerator.Index> BOTH = List.of(
    new DebianPinGenerator.Index(MAIN_SNAPSHOT, INDEX),
    new DebianPinGenerator.Index(SECURITY_SNAPSHOT, SECURITY_INDEX)
  );

  private DebianPinGenerator generator;

  @BeforeEach
  void setUp() {
    this.generator = new DebianPinGenerator();
  }

  @Test
  void shouldListEveryPackageAfterItsDependencies() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(MAIN, List.of("libroot1"));

    final List<String> names = closure.stream().map(DebianPinGenerator.IndexEntry::name).toList();
    final List<String> expected = List.of("libleaf0", "libmiddle2", "libalternative1", "libprovider3", "libroot1");
    assertEquals(expected, names);
  }

  @Test
  void shouldKeepTheFileAndHashOfEachPackage() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(MAIN, List.of("libleaf0"));

    final DebianPinGenerator.IndexEntry leaf = closure.getFirst();
    assertEquals("pool/main/l/leaf/libleaf0_0.1_arm64.deb", leaf.filename());
    assertEquals("3333", leaf.sha256());
  }

  @Test
  void shouldRefuseADependencyMissingFromTheIndex() {
    final List<String> roots = List.of("libmissing1");

    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> this.generator.resolveClosure(MAIN, roots));
    final String message = failure.getMessage();
    assertEquals("Package libmissing1 is missing from the indexes!", message);
  }

  @Test
  void shouldTakeANewerVersionFromTheSecurityArchive() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(BOTH, List.of("libleaf0"));

    final DebianPinGenerator.IndexEntry leaf = closure.getFirst();
    assertEquals("pool/updates/main/l/leaf/libleaf0_0.1-1+deb12u1_arm64.deb", leaf.filename());
    assertEquals("9999", leaf.sha256());
    assertEquals(SECURITY_SNAPSHOT, leaf.snapshot());
  }

  @Test
  void shouldKeepANewerVersionFromTheMainArchive() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(BOTH, List.of("libalternative1"));

    final DebianPinGenerator.IndexEntry alternative = closure.getFirst();
    assertEquals("pool/main/a/alternative/libalternative1_1_arm64.deb", alternative.filename());
    assertEquals(MAIN_SNAPSHOT, alternative.snapshot());
  }

  @Test
  void shouldKeepTheMainArchiveForTheSameVersion() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(BOTH, List.of("libunused1"));

    final DebianPinGenerator.IndexEntry unused = closure.getFirst();
    assertEquals(MAIN_SNAPSHOT, unused.snapshot());
  }
}
