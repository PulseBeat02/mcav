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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.brandonli.mcav.vm.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class QemuProgramTest {

  private static final Path EXECUTABLE = Path.of("qemu-system-x86_64");
  private static final Path OTHER_EXECUTABLE = Path.of("qemu-system-arm");
  private static final List<String> ARGUMENTS = List.of("-L", "data");
  private static final Map<String, String> ENVIRONMENT = Map.of("QEMU_MODULE_DIR", "modules");

  @Test
  void shouldHaveNoArgumentsOrEnvironmentForASystemProgram() {
    final QemuProgram program = QemuProgram.of(EXECUTABLE);

    final Path executable = program.getExecutable();
    final List<String> arguments = program.getArguments();
    final Map<String, String> environment = program.getEnvironment();
    assertEquals(EXECUTABLE, executable);
    assertEquals(List.of(), arguments);
    assertEquals(Map.of(), environment);
  }

  @Test
  void shouldCopyItsArgumentsAndEnvironment() {
    final List<String> givenArguments = new ArrayList<>(ARGUMENTS);
    final Map<String, String> givenEnvironment = new HashMap<>(ENVIRONMENT);
    final QemuProgram program = QemuProgram.of(EXECUTABLE, givenArguments, givenEnvironment);
    givenArguments.clear();
    givenEnvironment.clear();

    final Path executable = program.getExecutable();
    final List<String> arguments = program.getArguments();
    final Map<String, String> environment = program.getEnvironment();
    assertEquals(EXECUTABLE, executable);
    assertEquals(ARGUMENTS, arguments);
    assertEquals(ENVIRONMENT, environment);
  }

  @Test
  void shouldCompareEveryValue() {
    final QemuProgram program = QemuProgram.of(EXECUTABLE, ARGUMENTS, ENVIRONMENT);
    final QemuProgram equal = QemuProgram.of(EXECUTABLE, ARGUMENTS, ENVIRONMENT);
    final QemuProgram otherExecutable = QemuProgram.of(OTHER_EXECUTABLE, ARGUMENTS, ENVIRONMENT);
    final QemuProgram otherArguments = QemuProgram.of(EXECUTABLE, List.of(), ENVIRONMENT);
    final QemuProgram otherEnvironment = QemuProgram.of(EXECUTABLE, ARGUMENTS, Map.of());
    EqualityAssertions.assertEqualityContract(program, equal, otherExecutable, otherArguments, otherEnvironment);
  }

  @Test
  void shouldBuildTheHashCodeFromEveryValueInOrder() {
    final QemuProgram program = QemuProgram.of(EXECUTABLE, ARGUMENTS, ENVIRONMENT);

    final int hash = program.hashCode();
    final int expected = Objects.hash(EXECUTABLE, ARGUMENTS, ENVIRONMENT);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeItself() {
    final QemuProgram program = QemuProgram.of(EXECUTABLE, ARGUMENTS, ENVIRONMENT);

    final String text = program.toString();
    assertEquals("QemuProgram{executable=qemu-system-x86_64, arguments=[-L, data], environment={QEMU_MODULE_DIR=modules}}", text);
  }
}
