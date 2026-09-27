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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VMConfiguration}.
 */
final class VMConfigurationTest {

  @Test
  void startsEmpty() {
    final VMConfiguration configuration = VMConfiguration.builder();
    final List<String> arguments = configuration.getArguments();
    final String[] array = configuration.buildArgs();
    final String text = configuration.toString();
    final boolean argumentsIsEmpty = arguments.isEmpty();
    assertTrue(argumentsIsEmpty);
    assertEquals(0, array.length);
    assertEquals("", text);
  }

  @Test
  void createsANewConfigurationEveryTime() {
    final VMConfiguration first = VMConfiguration.builder();
    final VMConfiguration second = VMConfiguration.builder();
    first.memory(256);
    final boolean secondHasMemory = second.has("m");
    assertNotSame(first, second);
    assertFalse(secondHasMemory);
  }

  @Test
  void rendersEveryTypedOptionAsAQemuArgument() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(2048);
    configuration.cores(4);
    configuration.cdrom("alpine.iso");
    configuration.hda("disk.qcow2");
    configuration.hdb("data.qcow2");
    configuration.boot("d");
    configuration.network("user,model=virtio-net-pci");
    configuration.cpu("max");
    configuration.machine("q35");
    configuration.accelerator("tcg");
    configuration.vga("virtio");

    final List<String> arguments = configuration.getArguments();

    final List<String> expected = typedArguments();
    assertEquals(expected, arguments);
  }

  private static List<String> typedArguments() {
    return List.of(
      "-m",
      "2048M",
      "-smp",
      "4",
      "-cdrom",
      "alpine.iso",
      "-hda",
      "disk.qcow2",
      "-hdb",
      "data.qcow2",
      "-boot",
      "d",
      "-nic",
      "user,model=virtio-net-pci",
      "-cpu",
      "max",
      "-machine",
      "q35",
      "-accel",
      "tcg",
      "-vga",
      "virtio"
    );
  }

  @Test
  void setsMemoryWithAUnit() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(2, "G");
    final String memory = configuration.get("m");
    assertEquals("2G", memory);
  }

  @Test
  void replacesOptionsButKeepsTheirFirstPosition() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(512);
    configuration.cores(1);
    configuration.memory(1024);
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of("-m", "1024M", "-smp", "1");
    assertEquals(expectedArguments, arguments);
  }

  @Test
  void ordersOptionsBeforeRepeatableOptionsBeforeFlags() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.flag("enable-kvm");
    configuration.drive("file=a.qcow2,format=qcow2");
    configuration.device("virtio-rng-pci");
    configuration.drive("file=b.qcow2,format=qcow2");
    configuration.repeatable("object", "rng-random,id=rng0");
    configuration.option("name", "test");
    configuration.flag("snapshot");
    configuration.flag("enable-kvm");

    final List<String> arguments = configuration.getArguments();
    final List<String> expected = orderedArguments();
    assertEquals(expected, arguments);

    final String[] array = configuration.buildArgs();
    final String[] expectedArray = expected.toArray(new String[0]);
    assertArrayEquals(expectedArray, array);
    final String text = configuration.toString();
    final String expectedText = String.join(" ", expected);
    assertEquals(expectedText, text);
  }

  private static List<String> orderedArguments() {
    return List.of(
      "-name",
      "test",
      "-drive",
      "file=a.qcow2,format=qcow2",
      "-device",
      "virtio-rng-pci",
      "-drive",
      "file=b.qcow2,format=qcow2",
      "-object",
      "rng-random,id=rng0",
      "-enable-kvm",
      "-snapshot"
    );
  }

  @Test
  void answersWhichOptionsAndFlagsAreSet() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.cdrom("alpine.iso");
    configuration.flag("snapshot");
    configuration.device("usb-tablet");
    final boolean hasCdrom = configuration.has("cdrom");
    final boolean hasSnapshot = configuration.has("snapshot");
    final boolean hasDevice = configuration.has("device");
    final boolean hasMemory = configuration.has("m");
    final String cdrom = configuration.get("cdrom");
    final String snapshot = configuration.get("snapshot");
    final String memory = configuration.get("m");
    assertTrue(hasCdrom);
    assertTrue(hasSnapshot);
    // a repeatable option is set once it has a value, so the player sees a tablet the configuration added
    assertTrue(hasDevice);
    assertFalse(hasMemory);
    assertEquals("alpine.iso", cdrom);
    assertNull(snapshot);
    assertNull(memory);
  }

  @Test
  void listsTheValuesOfRepeatableOptions() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.device("usb-tablet");
    configuration.drive("file=disk.qcow2");
    configuration.device("virtio-net-pci");
    configuration.option("device", "not-repeatable");
    final List<String> devices = configuration.getAll("device");
    final List<String> drives = configuration.getAll("drive");
    final List<String> none = configuration.getAll("chardev");
    final String single = configuration.get("device");
    final List<String> expectedDevices = List.of("usb-tablet", "virtio-net-pci");
    final List<String> expectedDrives = List.of("file=disk.qcow2");
    final boolean noneIsEmpty = none.isEmpty();
    assertEquals(expectedDevices, devices);
    assertEquals(expectedDrives, drives);
    assertTrue(noneIsEmpty);
    assertEquals("not-repeatable", single);
    assertThrows(UnsupportedOperationException.class, devices::clear);
    assertThrows(NullPointerException.class, () -> configuration.getAll(null));
  }

  @Test
  void removesEveryValueOfARepeatableOption() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.device("usb-tablet");
    configuration.drive("file=disk.qcow2");
    configuration.device("virtio-net-pci");
    configuration.remove("device");
    final boolean hasDevice = configuration.has("device");
    final boolean hasDrive = configuration.has("drive");
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of("-drive", "file=disk.qcow2");
    assertFalse(hasDevice);
    assertTrue(hasDrive);
    assertEquals(expectedArguments, arguments);
  }

  @Test
  void removesOptionsAndFlags() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.cdrom("alpine.iso");
    configuration.flag("snapshot");
    configuration.cores(2);
    final VMConfiguration result = configuration.remove("cdrom");
    configuration.remove("snapshot");
    configuration.remove("not-set");
    final boolean hasCdrom = configuration.has("cdrom");
    final boolean hasSnapshot = configuration.has("snapshot");
    final List<String> arguments = configuration.getArguments();
    assertSame(configuration, result);
    assertFalse(hasCdrom);
    assertFalse(hasSnapshot);
    final List<String> expectedArguments = List.of("-smp", "2");
    assertEquals(expectedArguments, arguments);
  }

  @Test
  void returnsItselfFromEverySetter() {
    final VMConfiguration configuration = VMConfiguration.builder();
    final List<UnaryOperator<VMConfiguration>> setters = List.of(
      candidate -> candidate.memory(1),
      candidate -> candidate.memory(1, "G"),
      candidate -> candidate.cores(1),
      candidate -> candidate.cdrom("a"),
      candidate -> candidate.hda("a"),
      candidate -> candidate.hdb("a"),
      candidate -> candidate.boot("a"),
      candidate -> candidate.network("a"),
      candidate -> candidate.cpu("a"),
      candidate -> candidate.machine("a"),
      candidate -> candidate.accelerator("a"),
      candidate -> candidate.vga("a"),
      candidate -> candidate.drive("a"),
      candidate -> candidate.device("a"),
      candidate -> candidate.option("a", "b"),
      candidate -> candidate.repeatable("a", "b"),
      candidate -> candidate.flag("a")
    );
    for (final UnaryOperator<VMConfiguration> setter : setters) {
      final VMConfiguration result = setter.apply(configuration);
      assertSame(configuration, result);
    }
  }

  @Test
  void returnsArgumentsThatCannotBeModified() {
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(64);
    final List<String> arguments = configuration.getArguments();
    assertThrows(UnsupportedOperationException.class, arguments::clear);
    final String[] array = configuration.buildArgs();
    array[0] = "-changed";
    final List<String> again = configuration.getArguments();
    final List<String> expectedAgain = List.of("-m", "64M");
    assertEquals(expectedAgain, again);
  }

  @Test
  void rejectsInvalidTypedOptions() {
    final VMConfiguration configuration = VMConfiguration.builder();
    assertThrows(IllegalArgumentException.class, () -> configuration.memory(0));
    assertThrows(IllegalArgumentException.class, () -> configuration.memory(0, "G"));
    assertThrows(NullPointerException.class, () -> configuration.memory(1, null));
    assertThrows(IllegalArgumentException.class, () -> configuration.cores(0));
    assertThrows(NullPointerException.class, () -> configuration.cdrom(null));
    assertThrows(NullPointerException.class, () -> configuration.hda(null));
    assertThrows(NullPointerException.class, () -> configuration.hdb(null));
    assertThrows(NullPointerException.class, () -> configuration.boot(null));
    assertThrows(NullPointerException.class, () -> configuration.network(null));
    assertThrows(NullPointerException.class, () -> configuration.cpu(null));
    assertThrows(NullPointerException.class, () -> configuration.machine(null));
    assertThrows(NullPointerException.class, () -> configuration.accelerator(null));
    assertThrows(NullPointerException.class, () -> configuration.vga(null));
    assertThrows(NullPointerException.class, () -> configuration.drive(null));
    assertThrows(NullPointerException.class, () -> configuration.device(null));
    final List<String> arguments = configuration.getArguments();
    final boolean argumentsIsEmpty = arguments.isEmpty();
    assertTrue(argumentsIsEmpty);
  }

  @Test
  void rejectsInvalidGenericOptionsAndKeys() {
    final VMConfiguration configuration = VMConfiguration.builder();
    assertThrows(NullPointerException.class, () -> configuration.option(null, "value"));
    assertThrows(NullPointerException.class, () -> configuration.option("key", null));
    assertThrows(IllegalArgumentException.class, () -> configuration.option(" ", "value"));
    assertThrows(NullPointerException.class, () -> configuration.repeatable(null, "value"));
    assertThrows(NullPointerException.class, () -> configuration.repeatable("key", null));
    assertThrows(IllegalArgumentException.class, () -> configuration.repeatable("", "value"));
    assertThrows(NullPointerException.class, () -> configuration.flag(null));
    assertThrows(IllegalArgumentException.class, () -> configuration.flag(" "));
    assertThrows(NullPointerException.class, () -> configuration.remove(null));
    assertThrows(NullPointerException.class, () -> configuration.has(null));
    assertThrows(NullPointerException.class, () -> configuration.get(null));
    final List<String> arguments = configuration.getArguments();
    final boolean argumentsIsEmpty = arguments.isEmpty();
    assertTrue(argumentsIsEmpty);
  }
}
