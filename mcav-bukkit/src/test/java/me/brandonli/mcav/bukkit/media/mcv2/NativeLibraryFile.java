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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

record NativeLibraryFile(NativeLibraryFile.Format format, int machine, Set<String> exports, List<String> needed, Set<String> undefined) {
  private static final int ELF_MAGIC = 0x464c457f;
  private static final int ELF_CLASS_OFFSET = 4;
  private static final int ELF_CLASS_64 = 2;
  private static final int ELF_BYTE_ORDER_OFFSET = 5;
  private static final int ELF_LITTLE_ENDIAN = 1;
  private static final int ELF_MACHINE_OFFSET = 18;
  private static final int ELF_SECTION_HEADERS_OFFSET = 0x28;
  private static final int ELF_SECTION_HEADER_SIZE_OFFSET = 0x3a;
  private static final int ELF_SECTION_COUNT_OFFSET = 0x3c;
  private static final int ELF_SECTION_TYPE_OFFSET = 4;
  private static final int ELF_SECTION_START_OFFSET = 24;
  private static final int ELF_SECTION_SIZE_OFFSET = 32;
  private static final int ELF_SECTION_LINK_OFFSET = 40;
  private static final int ELF_SYMBOL_SIZE = 24;
  private static final int ELF_SYMBOL_INFO_OFFSET = 4;
  private static final int ELF_SYMBOL_VISIBILITY_OFFSET = 5;
  private static final int ELF_SYMBOL_SECTION_OFFSET = 6;
  private static final int ELF_BINDING_SHIFT = 4;
  private static final int ELF_VISIBILITY_MASK = 3;
  private static final int ELF_DYNAMIC_SIZE = 16;
  private static final int ELF_DYNAMIC_VALUE_OFFSET = 8;
  private static final int SHT_DYNAMIC = 6;
  private static final int SHT_DYNSYM = 11;
  private static final int SHN_UNDEF = 0;
  private static final int STB_GLOBAL = 1;
  private static final int STB_WEAK = 2;
  private static final int STV_DEFAULT = 0;
  private static final long DT_NEEDED = 1;

  private static final short MZ = 0x5a4d;
  private static final int PE_HEADER_POINTER_OFFSET = 0x3c;
  private static final int PE_SIGNATURE = 0x00004550;
  private static final int PE_MACHINE_OFFSET = 4;
  private static final int PE_SECTION_COUNT_OFFSET = 6;
  private static final int PE_OPTIONAL_HEADER_SIZE_OFFSET = 20;
  private static final int PE_OPTIONAL_HEADER_OFFSET = 24;
  private static final short PE32_PLUS = 0x20b;
  private static final int EXPORT_TABLE = 112;
  private static final int PE_EXPORT_NAME_COUNT_OFFSET = 24;
  private static final int PE_EXPORT_NAMES_OFFSET = 32;
  private static final int PE_SECTION_SIZE = 40;
  private static final int PE_SECTION_VIRTUAL_SIZE_OFFSET = 8;
  private static final int PE_SECTION_ADDRESS_OFFSET = 12;
  private static final int PE_SECTION_RAW_SIZE_OFFSET = 16;
  private static final int PE_SECTION_RAW_START_OFFSET = 20;

  private static final int MACH_O_64 = 0xfeedfacf;
  private static final int MACH_O_MACHINE_OFFSET = 4;
  private static final int MACH_O_COMMAND_COUNT_OFFSET = 16;
  private static final int MACH_HEADER_64_SIZE = 32;
  private static final int MACH_O_COMMAND_SIZE_OFFSET = 4;
  private static final int LC_SYMTAB = 0x2;
  private static final int MACH_O_SYMBOLS_OFFSET = 8;
  private static final int MACH_O_SYMBOL_COUNT_OFFSET = 12;
  private static final int MACH_O_NAMES_OFFSET = 16;
  private static final int NLIST_64_SIZE = 16;
  private static final int MACH_O_SYMBOL_TYPE_OFFSET = 4;
  private static final int N_STAB = 0xe0;
  private static final int N_PEXT = 0x10;
  private static final int N_TYPE = 0x0e;
  private static final int N_SECT = 0x0e;
  private static final int N_EXT = 0x01;
  private static final String MACH_O_SYMBOL_PREFIX = "_";

  static NativeLibraryFile read(final byte[] bytes) {
    final ByteBuffer file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    final int magic = file.getInt(0);
    if (magic == ELF_MAGIC) {
      return readElf(file);
    }

    if (magic == MACH_O_64) {
      return readMachO(file);
    }

    if (file.getShort(0) == MZ) {
      return readPe(file);
    }

    final String message = "Not a 64-bit ELF, PE or Mach-O library!";
    throw new IllegalArgumentException(message);
  }

  private static NativeLibraryFile readElf(final ByteBuffer file) {
    if (file.get(ELF_CLASS_OFFSET) != ELF_CLASS_64 || file.get(ELF_BYTE_ORDER_OFFSET) != ELF_LITTLE_ENDIAN) {
      final String message = "Not a 64-bit little-endian ELF file!";
      throw new IllegalArgumentException(message);
    }

    final int machine = Short.toUnsignedInt(file.getShort(ELF_MACHINE_OFFSET));
    final int sectionHeaders = Math.toIntExact(file.getLong(ELF_SECTION_HEADERS_OFFSET));
    final int headerSize = Short.toUnsignedInt(file.getShort(ELF_SECTION_HEADER_SIZE_OFFSET));
    final int sections = Short.toUnsignedInt(file.getShort(ELF_SECTION_COUNT_OFFSET));
    final Set<String> exports = new TreeSet<>();
    final Set<String> undefined = new TreeSet<>();
    final List<String> needed = new ArrayList<>();
    for (int section = 0; section < sections; section++) {
      final int header = sectionHeaders + section * headerSize;
      final int type = file.getInt(header + ELF_SECTION_TYPE_OFFSET);
      final int start = Math.toIntExact(file.getLong(header + ELF_SECTION_START_OFFSET));
      final int end = start + Math.toIntExact(file.getLong(header + ELF_SECTION_SIZE_OFFSET));
      final int linkedSection = file.getInt(header + ELF_SECTION_LINK_OFFSET);
      final int linkedHeader = sectionHeaders + linkedSection * headerSize;
      final int names = Math.toIntExact(file.getLong(linkedHeader + ELF_SECTION_START_OFFSET));
      if (type == SHT_DYNSYM) {
        readDynamicSymbols(file, start, end, names, exports, undefined);
      } else if (type == SHT_DYNAMIC) {
        readNeeded(file, start, end, names, needed);
      }
    }

    return new NativeLibraryFile(Format.ELF, machine, exports, needed, undefined);
  }

  private static void readDynamicSymbols(
    final ByteBuffer file,
    final int start,
    final int end,
    final int names,
    final Set<String> exports,
    final Set<String> undefined
  ) {
    // the first entry is the reserved null symbol
    for (int symbol = start + ELF_SYMBOL_SIZE; symbol < end; symbol += ELF_SYMBOL_SIZE) {
      final String name = readString(file, names + file.getInt(symbol));
      final int binding = Byte.toUnsignedInt(file.get(symbol + ELF_SYMBOL_INFO_OFFSET)) >>> ELF_BINDING_SHIFT;
      final int visibility = file.get(symbol + ELF_SYMBOL_VISIBILITY_OFFSET) & ELF_VISIBILITY_MASK;
      if (Short.toUnsignedInt(file.getShort(symbol + ELF_SYMBOL_SECTION_OFFSET)) == SHN_UNDEF) {
        undefined.add(name);
      } else if ((binding == STB_GLOBAL || binding == STB_WEAK) && visibility == STV_DEFAULT) {
        exports.add(name);
      }
    }
  }

  private static void readNeeded(final ByteBuffer file, final int start, final int end, final int names, final List<String> needed) {
    for (int entry = start; entry < end; entry += ELF_DYNAMIC_SIZE) {
      if (file.getLong(entry) == DT_NEEDED) {
        needed.add(readString(file, names + Math.toIntExact(file.getLong(entry + ELF_DYNAMIC_VALUE_OFFSET))));
      }
    }
  }

  private static NativeLibraryFile readPe(final ByteBuffer file) {
    final int signature = file.getInt(PE_HEADER_POINTER_OFFSET);
    if (file.getInt(signature) != PE_SIGNATURE) {
      final String message = "No PE signature!";
      throw new IllegalArgumentException(message);
    }

    final int machine = Short.toUnsignedInt(file.getShort(signature + PE_MACHINE_OFFSET));
    final int sections = Short.toUnsignedInt(file.getShort(signature + PE_SECTION_COUNT_OFFSET));
    final int optionalHeader = signature + PE_OPTIONAL_HEADER_OFFSET;
    if (file.getShort(optionalHeader) != PE32_PLUS) {
      final String message = "Not a PE32+ file!";
      throw new IllegalArgumentException(message);
    }

    final int sectionTable = optionalHeader + Short.toUnsignedInt(file.getShort(signature + PE_OPTIONAL_HEADER_SIZE_OFFSET));
    final int exportTable = file.getInt(optionalHeader + EXPORT_TABLE);
    final Set<String> exports = new TreeSet<>();
    if (exportTable != 0) {
      final int directory = toFileOffset(file, sectionTable, sections, exportTable);
      final int count = file.getInt(directory + PE_EXPORT_NAME_COUNT_OFFSET);
      final int names = toFileOffset(file, sectionTable, sections, file.getInt(directory + PE_EXPORT_NAMES_OFFSET));
      for (int nameIndex = 0; nameIndex < count; nameIndex++) {
        exports.add(readString(file, toFileOffset(file, sectionTable, sections, file.getInt(names + Integer.BYTES * nameIndex))));
      }
    }

    return new NativeLibraryFile(Format.PE, machine, exports, List.of(), Set.of());
  }

  private static int toFileOffset(final ByteBuffer file, final int sectionTable, final int sections, final int address) {
    for (int section = 0; section < sections; section++) {
      final int header = sectionTable + section * PE_SECTION_SIZE;
      final int start = file.getInt(header + PE_SECTION_ADDRESS_OFFSET);
      final int size = Math.max(file.getInt(header + PE_SECTION_VIRTUAL_SIZE_OFFSET), file.getInt(header + PE_SECTION_RAW_SIZE_OFFSET));
      if (address >= start && address < start + size) {
        return address - start + file.getInt(header + PE_SECTION_RAW_START_OFFSET);
      }
    }

    final String message = "Address %s is mapped by no section!".formatted(address);
    throw new IllegalArgumentException(message);
  }

  private static NativeLibraryFile readMachO(final ByteBuffer file) {
    final int machine = file.getInt(MACH_O_MACHINE_OFFSET);
    final int commands = file.getInt(MACH_O_COMMAND_COUNT_OFFSET);
    final Set<String> exports = new TreeSet<>();
    int command = MACH_HEADER_64_SIZE;
    for (int index = 0; index < commands; index++) {
      if (file.getInt(command) == LC_SYMTAB) {
        readSymbolTable(file, command, exports);
      }

      command += file.getInt(command + MACH_O_COMMAND_SIZE_OFFSET);
    }

    return new NativeLibraryFile(Format.MACH_O, machine, exports, List.of(), Set.of());
  }

  private static void readSymbolTable(final ByteBuffer file, final int command, final Set<String> exports) {
    final int symbols = file.getInt(command + MACH_O_SYMBOLS_OFFSET);
    final int count = file.getInt(command + MACH_O_SYMBOL_COUNT_OFFSET);
    final int names = file.getInt(command + MACH_O_NAMES_OFFSET);
    for (int symbol = 0; symbol < count; symbol++) {
      final int entry = symbols + symbol * NLIST_64_SIZE;
      final int type = Byte.toUnsignedInt(file.get(entry + MACH_O_SYMBOL_TYPE_OFFSET));
      if ((type & N_STAB) == 0 && (type & N_PEXT) == 0 && (type & N_EXT) != 0 && (type & N_TYPE) == N_SECT) {
        exports.add(readString(file, names + file.getInt(entry)).substring(MACH_O_SYMBOL_PREFIX.length()));
      }
    }
  }

  private static String readString(final ByteBuffer file, final int offset) {
    int end = offset;
    while (file.get(end) != 0) {
      end++;
    }

    final byte[] name = new byte[end - offset];
    file.get(offset, name);
    return new String(name, StandardCharsets.US_ASCII);
  }

  enum Format {
    ELF,
    PE,
    MACH_O,
  }
}
