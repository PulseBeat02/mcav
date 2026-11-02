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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import me.brandonli.mcav.vm.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class VmConfigurationTest {

  private static final String MEMORY_OPTION = "m";
  private static final String CDROM_OPTION = "cdrom";
  private static final String DEVICE_OPTION = "device";
  private static final String DRIVE_OPTION = "drive";
  private static final String SNAPSHOT_FLAG = "snapshot";
  private static final String KVM_FLAG = "enable-kvm";

  private static final String MEMORY_ARGUMENT = "-m";
  private static final String CORES_ARGUMENT = "-smp";
  private static final String DRIVE_ARGUMENT = "-drive";

  private static final String GIGABYTES = "G";

  private static final int MEMORY_MEGABYTES = 64;
  private static final int OTHER_MEMORY_MEGABYTES = 128;

  private static final String CDROM_IMAGE = "alpine.iso";
  private static final String FIRST_DISK = "disk.qcow2";
  private static final String SECOND_DISK = "data.qcow2";

  private static final String TABLET_DEVICE = "usb-tablet";
  private static final String NETWORK_DEVICE = "virtio-net-pci";
  private static final String DISK_DRIVE = "file=disk.qcow2";
  private static final String OTHER_DRIVE = "file=data.qcow2";

  @Test
  void shouldStartEmpty() {
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final List<String> arguments = configuration.getArguments();
    final String[] array = configuration.buildArguments();
    final String text = configuration.toString();
    final boolean argumentsIsEmpty = arguments.isEmpty();
    assertTrue(argumentsIsEmpty);
    assertEquals(0, array.length);
    assertEquals("VmConfiguration{options={}, repeatable=[], flags=[]}", text);
  }

  @Test
  void shouldCreateANewBuilderEveryTime() {
    final VmConfigurationBuilder first = VmConfiguration.builder();
    final VmConfigurationBuilder second = VmConfiguration.builder();
    first.memory(256);
    final VmConfiguration configuration = second.build();
    final boolean hasMemory = configuration.has(MEMORY_OPTION);
    assertNotSame(first, second);
    assertFalse(hasMemory);
  }

  @Test
  void shouldKeepItsOptionsWhenTheBuilderChangesAfterwards() {
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    builder.memory(MEMORY_MEGABYTES);
    final VmConfiguration configuration = builder.build();
    builder.memory(OTHER_MEMORY_MEGABYTES);
    builder.drive(DISK_DRIVE);
    builder.flag(SNAPSHOT_FLAG);
    final List<String> arguments = configuration.getArguments();
    final List<String> expected = List.of(MEMORY_ARGUMENT, "64M");
    assertEquals(expected, arguments);
  }

  @Test
  void shouldRenderEveryTypedOptionAsAQemuArgument() {
    final VmConfiguration configuration = VmConfiguration.builder()
      .memory(2048)
      .cores(4)
      .cdrom(CDROM_IMAGE)
      .firstHardDisk(FIRST_DISK)
      .secondHardDisk(SECOND_DISK)
      .boot("d")
      .network("user,model=virtio-net-pci")
      .cpu("max")
      .machine("q35")
      .accelerator("tcg")
      .vga("virtio")
      .build();

    final List<String> arguments = configuration.getArguments();

    final List<String> expected = this.typedArguments();
    assertEquals(expected, arguments);
  }

  private List<String> typedArguments() {
    return List.of(
      MEMORY_ARGUMENT,
      "2048M",
      CORES_ARGUMENT,
      "4",
      "-cdrom",
      CDROM_IMAGE,
      "-hda",
      FIRST_DISK,
      "-hdb",
      SECOND_DISK,
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
  void shouldSetMemoryWithAUnit() {
    final VmConfiguration configuration = VmConfiguration.builder().memory(2, GIGABYTES).build();
    final Optional<String> memory = configuration.find(MEMORY_OPTION);
    final Optional<String> expected = Optional.of("2G");
    assertEquals(expected, memory);
  }

  @Test
  void shouldReplaceOptionsButKeepTheirFirstPosition() {
    final VmConfiguration configuration = VmConfiguration.builder().memory(512).cores(1).memory(1024).build();
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of(MEMORY_ARGUMENT, "1024M", CORES_ARGUMENT, "1");
    assertEquals(expectedArguments, arguments);
  }

  @Test
  void shouldOrderOptionsBeforeRepeatableOptionsBeforeFlags() {
    final VmConfiguration configuration = VmConfiguration.builder()
      .flag(KVM_FLAG)
      .drive("file=a.qcow2,format=qcow2")
      .device("virtio-rng-pci")
      .drive("file=b.qcow2,format=qcow2")
      .repeatable("object", "rng-random,id=rng0")
      .option("name", "test")
      .flag(SNAPSHOT_FLAG)
      .flag(KVM_FLAG)
      .build();

    final List<String> arguments = configuration.getArguments();
    final List<String> expected = this.orderedArguments();
    assertEquals(expected, arguments);

    final String[] array = configuration.buildArguments();
    final String[] expectedArray = expected.toArray(new String[0]);
    assertArrayEquals(expectedArray, array);
  }

  private List<String> orderedArguments() {
    return List.of(
      "-name",
      "test",
      DRIVE_ARGUMENT,
      "file=a.qcow2,format=qcow2",
      "-device",
      "virtio-rng-pci",
      DRIVE_ARGUMENT,
      "file=b.qcow2,format=qcow2",
      "-object",
      "rng-random,id=rng0",
      "-enable-kvm",
      "-snapshot"
    );
  }

  @Test
  void shouldAnswerWhichOptionsAndFlagsAreSet() {
    final VmConfiguration configuration = VmConfiguration.builder().cdrom(CDROM_IMAGE).flag(SNAPSHOT_FLAG).device(TABLET_DEVICE).build();
    final boolean hasCdrom = configuration.has(CDROM_OPTION);
    final boolean hasSnapshot = configuration.has(SNAPSHOT_FLAG);
    final boolean hasDevice = configuration.has(DEVICE_OPTION);
    final boolean hasMemory = configuration.has(MEMORY_OPTION);
    final Optional<String> cdrom = configuration.find(CDROM_OPTION);
    final Optional<String> snapshot = configuration.find(SNAPSHOT_FLAG);
    final Optional<String> memory = configuration.find(MEMORY_OPTION);
    final Optional<String> expectedCdrom = Optional.of(CDROM_IMAGE);
    final Optional<String> nothing = Optional.empty();
    assertTrue(hasCdrom);
    assertTrue(hasSnapshot);
    assertTrue(hasDevice);
    assertFalse(hasMemory);
    assertEquals(expectedCdrom, cdrom);
    assertEquals(nothing, snapshot);
    assertEquals(nothing, memory);
  }

  @Test
  void shouldListTheValuesOfRepeatableOptions() {
    final VmConfiguration configuration = VmConfiguration.builder()
      .device(TABLET_DEVICE)
      .drive(DISK_DRIVE)
      .device(NETWORK_DEVICE)
      .option(DEVICE_OPTION, "not-repeatable")
      .build();
    final List<String> devices = configuration.getAll(DEVICE_OPTION);
    final List<String> drives = configuration.getAll(DRIVE_OPTION);
    final List<String> none = configuration.getAll("chardev");
    final Optional<String> single = configuration.find(DEVICE_OPTION);
    final Optional<String> expectedSingle = Optional.of("not-repeatable");
    final List<String> expectedDevices = List.of(TABLET_DEVICE, NETWORK_DEVICE);
    final List<String> expectedDrives = List.of(DISK_DRIVE);
    final boolean noneIsEmpty = none.isEmpty();
    assertEquals(expectedDevices, devices);
    assertEquals(expectedDrives, drives);
    assertTrue(noneIsEmpty);
    assertEquals(expectedSingle, single);
    assertThrows(UnsupportedOperationException.class, devices::clear);
    assertThrows(NullPointerException.class, () -> configuration.getAll(null));
  }

  @Test
  void shouldRemoveEveryValueOfARepeatableOption() {
    final VmConfiguration configuration = VmConfiguration.builder()
      .device(TABLET_DEVICE)
      .drive(DISK_DRIVE)
      .device(NETWORK_DEVICE)
      .remove(DEVICE_OPTION)
      .build();
    final boolean hasDevice = configuration.has(DEVICE_OPTION);
    final boolean hasDrive = configuration.has(DRIVE_OPTION);
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of(DRIVE_ARGUMENT, DISK_DRIVE);
    assertFalse(hasDevice);
    assertTrue(hasDrive);
    assertEquals(expectedArguments, arguments);
  }

  @Test
  void shouldRemoveOptionsAndFlags() {
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    builder.cdrom(CDROM_IMAGE);
    builder.flag(SNAPSHOT_FLAG);
    builder.cores(2);
    final VmConfigurationBuilder result = builder.remove(CDROM_OPTION);
    builder.remove(SNAPSHOT_FLAG);
    builder.remove("not-set");
    final VmConfiguration configuration = builder.build();
    final boolean hasCdrom = configuration.has(CDROM_OPTION);
    final boolean hasSnapshot = configuration.has(SNAPSHOT_FLAG);
    final List<String> arguments = configuration.getArguments();
    assertSame(builder, result);
    assertFalse(hasCdrom);
    assertFalse(hasSnapshot);

    final List<String> expectedArguments = List.of(CORES_ARGUMENT, "2");
    assertEquals(expectedArguments, arguments);
  }

  @Test
  void shouldReturnItselfFromEverySetter() {
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    final List<UnaryOperator<VmConfigurationBuilder>> setters = List.of(
      candidate -> candidate.memory(1),
      candidate -> candidate.memory(1, GIGABYTES),
      candidate -> candidate.cores(1),
      candidate -> candidate.cdrom("a"),
      candidate -> candidate.firstHardDisk("a"),
      candidate -> candidate.secondHardDisk("a"),
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
    for (final UnaryOperator<VmConfigurationBuilder> setter : setters) {
      final VmConfigurationBuilder result = setter.apply(builder);
      assertSame(builder, result);
    }
  }

  @Test
  void shouldReturnArgumentsThatCannotBeModified() {
    final VmConfiguration configuration = VmConfiguration.builder().memory(MEMORY_MEGABYTES).build();
    final List<String> arguments = configuration.getArguments();
    assertThrows(UnsupportedOperationException.class, arguments::clear);

    final String[] array = configuration.buildArguments();
    array[0] = "-changed";
    final List<String> again = configuration.getArguments();
    final List<String> expectedAgain = List.of(MEMORY_ARGUMENT, "64M");
    assertEquals(expectedAgain, again);
  }

  @Test
  void shouldRejectInvalidTypedOptions() {
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    assertThrows(IllegalArgumentException.class, () -> builder.memory(0));
    assertThrows(IllegalArgumentException.class, () -> builder.memory(0, GIGABYTES));
    assertThrows(NullPointerException.class, () -> builder.memory(1, null));
    assertThrows(IllegalArgumentException.class, () -> builder.cores(0));
    assertThrows(NullPointerException.class, () -> builder.cdrom(null));
    assertThrows(NullPointerException.class, () -> builder.firstHardDisk(null));
    assertThrows(NullPointerException.class, () -> builder.secondHardDisk(null));
    assertThrows(NullPointerException.class, () -> builder.boot(null));
    assertThrows(NullPointerException.class, () -> builder.network(null));
    assertThrows(NullPointerException.class, () -> builder.cpu(null));
    assertThrows(NullPointerException.class, () -> builder.machine(null));
    assertThrows(NullPointerException.class, () -> builder.accelerator(null));
    assertThrows(NullPointerException.class, () -> builder.vga(null));
    assertThrows(NullPointerException.class, () -> builder.drive(null));
    assertThrows(NullPointerException.class, () -> builder.device(null));

    final VmConfiguration configuration = builder.build();
    final List<String> arguments = configuration.getArguments();
    final boolean argumentsIsEmpty = arguments.isEmpty();
    assertTrue(argumentsIsEmpty);
  }

  @Test
  void shouldRejectInvalidGenericOptionsAndKeys() {
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    assertThrows(NullPointerException.class, () -> builder.option(null, "value"));
    assertThrows(NullPointerException.class, () -> builder.option("key", null));
    assertThrows(IllegalArgumentException.class, () -> builder.option(" ", "value"));
    assertThrows(NullPointerException.class, () -> builder.repeatable(null, "value"));
    assertThrows(NullPointerException.class, () -> builder.repeatable("key", null));
    assertThrows(IllegalArgumentException.class, () -> builder.repeatable("", "value"));
    assertThrows(NullPointerException.class, () -> builder.flag(null));
    assertThrows(IllegalArgumentException.class, () -> builder.flag(" "));
    assertThrows(NullPointerException.class, () -> builder.remove(null));

    final VmConfiguration configuration = builder.build();
    assertThrows(NullPointerException.class, () -> configuration.has(null));
    assertThrows(NullPointerException.class, () -> configuration.find(null));

    final List<String> arguments = configuration.getArguments();
    final boolean argumentsIsEmpty = arguments.isEmpty();
    assertTrue(argumentsIsEmpty);
  }

  @Test
  void shouldBeEqualToAConfigurationWithTheSameOptions() {
    final VmConfiguration configuration = this.configuration(MEMORY_MEGABYTES, DISK_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration equal = this.configuration(MEMORY_MEGABYTES, DISK_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration otherMemory = this.configuration(OTHER_MEMORY_MEGABYTES, DISK_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration otherDrive = this.configuration(MEMORY_MEGABYTES, OTHER_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration otherFlag = this.configuration(MEMORY_MEGABYTES, DISK_DRIVE, KVM_FLAG);
    EqualityAssertions.assertEqualityContract(configuration, equal, otherMemory, otherDrive, otherFlag);
  }

  private VmConfiguration configuration(final int memoryMegabytes, final String drive, final String flag) {
    return VmConfiguration.builder().memory(memoryMegabytes).drive(drive).flag(flag).build();
  }

  @Test
  void shouldGiveConfigurationsThatDifferInOneOptionDifferentHashCodes() {
    final VmConfiguration configuration = this.configuration(MEMORY_MEGABYTES, DISK_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration otherMemory = this.configuration(OTHER_MEMORY_MEGABYTES, DISK_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration otherDrive = this.configuration(MEMORY_MEGABYTES, OTHER_DRIVE, SNAPSHOT_FLAG);
    final VmConfiguration otherFlag = this.configuration(MEMORY_MEGABYTES, DISK_DRIVE, KVM_FLAG);
    final int hash = configuration.hashCode();
    final int memoryHash = otherMemory.hashCode();
    final int driveHash = otherDrive.hashCode();
    final int flagHash = otherFlag.hashCode();
    assertNotEquals(hash, memoryHash);
    assertNotEquals(hash, driveHash);
    assertNotEquals(hash, flagHash);
  }

  @Test
  void shouldDescribeItsOptionsRepeatableOptionsAndFlags() {
    final VmConfiguration configuration = this.configuration(MEMORY_MEGABYTES, DISK_DRIVE, SNAPSHOT_FLAG);
    final String text = configuration.toString();
    assertEquals("VmConfiguration{options={m=64M}, repeatable=[-drive, file=disk.qcow2], flags=[snapshot]}", text);
  }
}
