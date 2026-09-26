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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests {@link QemuHardwareValues}.
 */
final class QemuHardwareValuesTest {

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "machine|pc",
      "machine|q35,accel=kvm:tcg,usb=on,smm=off,vmport=auto,hpet=off,acpi=on,graphics=off,kernel-irqchip=split",
      "machine|virt,gic-version=3,highmem=on,virtualization=on,secure=off,its=on,iommu=smmuv3",
      "machine|virt,aia=aplic-imsic,mem-merge=off,dump-guest-core=off",
      "machine|type=pc-i440fx-8.2,sata=on,pic=on,pit=on,i8042=off",
      "m|512",
      "m|2G,slots=2,maxmem=8G",
      "m|size=1024M",
      "smp|4",
      "smp|cpus=8,maxcpus=8,sockets=1,dies=1,clusters=1,cores=4,threads=2",
      "accel|kvm",
      "accel|tcg,thread=multi,tb-size=512,split-wx=off,one-insn-per-tb=off,dirty-ring-size=4096,kernel-irqchip=on",
      "boot|d",
      "boot|order=dc,once=d,menu=on,strict=off,reboot-timeout=-1,splash-time=500",
      "name|my vm",
      "name|guest=vm,process=qemu-vm,debug-threads=on",
      "rtc|base=utc,clock=host,driftfix=slew",
      "rtc|base=2026-09-25T12:00:00",
      "cpu|host",
      "cpu|max,+ssse3,-avx,pmu=off",
      "k|en-us",
      "k|de",
      "vga|virtio",
    }
  )
  void acceptsTheValuesOfItsForms(final String name, final String value) {
    QemuHardwareValues.check(name, value);
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "machine|virt,dumpdtb=tree.dtb",
      "machine|pc,firmware=server.properties",
      "machine|q35,kernel=bzImage,initrd=ramdisk",
      "machine|virt,dtb=board.dtb",
      "machine|pc,pcspk-audiodev=snd0",
      "machine|pc,memory-backend=ram0",
      "machine|pc,usb",
      "machine|pc,usb=maybe",
      "machine|type=../pc",
      "machine|/pc",
      "m|lots",
      "smp|4,cores=two",
      "accel|kvm,notify-vmexit=run",
      "boot|order=c,splash=logo.bmp",
      "boot|order=z",
      "name|C:\\\\windows",
      "name|vm,process=a/b",
      "rtc|base=tomorrow",
      "rtc|utc",
      "rtc|base",
      "cpu|host,+sse/2",
      "cpu|../cpu",
      "cpu|pc,splash=dx",
      "cpu|host,kernel=vmlinuz",
      "cpu|host,pcspk-audiodev=snd0",
      "k|en-us,x",
      "k|../../keymaps",
      "vga|std,x",
    }
  )
  void refusesEveryOtherValue(final String name, final String value) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.check(name, value));
    assertEquals(true, refused.getMessage().startsWith("The QEMU option -" + name + " does not allow"), refused.getMessage());
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "format=raw",
      "format=qcow2",
      "format=vmdk",
      "format=vdi",
      "format=vhdx",
      "format=vpc",
      "if=ide",
      "if=virtio",
      "if=floppy",
      "if=none",
      "media=disk",
      "media=cdrom",
      "index=1",
      "bus=0",
      "unit=1",
      "id=disk0",
      "serial=ABC-1",
      "cache=none",
      "cache=writeback",
      "aio=io_uring",
      "snapshot=on",
      "readonly=off",
      "copy-on-read=on",
      "discard=unmap",
      "detect-zeroes=unmap",
      "werror=enospc",
      "rerror=report",
    }
  )
  void acceptsThePropertiesThatSayHowADriveIsAttached(final String part) {
    QemuHardwareValues.checkDriveProperty(part, "file=disk.img," + part);
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "driver=file",
      "node-name=disk",
      "backing=none",
      "file.locking=off",
      "throttling.iops-total=100",
      "format=json",
      "format=",
      "if=mtd",
      "media",
      "index=one",
      "cache=fast",
      "snapshot=yes",
      "werror=enospc,rerror=enospc",
    }
  )
  void refusesEveryOtherPropertyOfADrive(final String part) {
    final String value = "file=disk.img," + part;
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      QemuHardwareValues.checkDriveProperty(part, value)
    );
    assertEquals(true, refused.getMessage().startsWith("The QEMU option -drive does not allow"), refused.getMessage());
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "512|536870912",
      "512M|536870912",
      "512m|536870912",
      "2G|2147483648",
      "2g|2147483648",
      "4194304K|4294967296",
      "1T|1099511627776",
      "size=1024M,slots=2,maxmem=4G|1073741824",
      "2G,slots=2,maxmem=8G|2147483648",
      "slots=2,maxmem=8G|134217728",
      "999999999T|9223372036854775807",
      "999999999|1048575998951424",
    }
  )
  void theMemoryOfAMachineIsReadAsQemuReadsIt(final String value, final long bytes) {
    assertEquals(bytes, QemuHardwareValues.memoryBytes(value));
  }

  @Test
  void onlyHardwareOptionsAreChecked() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.check("fda", "a"));
    assertEquals("The QEMU option -fda is not a hardware option", refused.getMessage());
  }

  @Test
  void theValuesAreNotInstantiable() throws ReflectiveOperationException {
    final Constructor<QemuHardwareValues> constructor = QemuHardwareValues.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    final InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
    assertEquals(UnsupportedOperationException.class, failure.getCause().getClass());
  }
}
