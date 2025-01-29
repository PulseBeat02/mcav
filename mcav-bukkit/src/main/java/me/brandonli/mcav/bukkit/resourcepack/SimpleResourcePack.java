/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.bukkit.resourcepack;

import static net.kyori.adventure.key.Key.key;

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import net.kyori.adventure.key.Key;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.base.Writable;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackWriter;
import team.unnamed.creative.sound.Sound;
import team.unnamed.creative.sound.SoundEntry;
import team.unnamed.creative.sound.SoundEvent;

/**
 * A utility class that provides an abstraction for creating and managing a simple resource pack.
 * This class enables the addition of sounds, external files, metadata, and the generation of zip files
 * for Minecraft resource packs.
 * <p>
 * Instances of this class can be created using the {@code create()} method.
 */
public final class SimpleResourcePack {

  private final ResourcePack resourcePack;

  /**
   * Constructs a new instance of {@code SimpleResourcePack}.
   * <p>
   * This constructor initializes a {@code ResourcePack} object using the static factory method
   * {@code ResourcePack.resourcePack()} and assigns it to the internal field.
   * <p>
   * Note: This constructor is package-private and is intended to be used internally by the
   * {@code SimpleResourcePack} class and its associated methods.
   */
  SimpleResourcePack() {
    this.resourcePack = ResourcePack.resourcePack();
  }

  /**
   * Creates a new instance of {@code SimpleResourcePack}.
   *
   * @return a new {@code SimpleResourcePack} instance
   */
  public static SimpleResourcePack pack() {
    return new SimpleResourcePack();
  }

  /**
   * Registers a sound to the resource pack using the specified name and file path.
   * The method creates a sound key, associates it with the provided file path,
   * and sets up the necessary sound and sound event for inclusion in the resource pack.
   *
   * @param raw  the string identifier for the sound that will be used as its key
   * @param path the file path pointing to the sound file to be added
   * @throws NullPointerException if {@code raw} or {@code path} is null
   */
  public void sound(final String raw, final Path path) {
    Preconditions.checkNotNull(raw);
    Preconditions.checkNotNull(path);
    final Key key = key(raw);
    final Sound sound = Sound.sound(key, Writable.path(path));
    final SoundEntry soundEntry = SoundEntry.soundEntry().type(SoundEntry.Type.FILE).key(key).build();
    final SoundEvent soundEvent = SoundEvent.soundEvent().key(key).sounds(soundEntry).replace(false).build();
    this.resourcePack.sound(sound);
    this.resourcePack.soundEvent(soundEvent);
  }

  /**
   * Adds an external file to the resource pack at the specified path. This method allows the inclusion
   * of arbitrary files that are not part of other predefined resource types.
   *
   * @param path The location within the resource pack where the file will be stored. It must not be null.
   * @param file The path to the external file being added. It must not be null.
   */
  public void external(final String path, final Path file) {
    Preconditions.checkNotNull(path);
    Preconditions.checkNotNull(file);
    this.resourcePack.unknownFile(path, Writable.path(file));
  }

  /**
   * Sets metadata for the resource pack, including the format version and a description.
   *
   * @param format      the format version of the resource pack
   * @param description the description text of the resource pack; must not be null
   */
  public void meta(final int format, final String description) {
    Preconditions.checkNotNull(description);
    this.resourcePack.packMeta(format, description);
  }

  /**
   * Creates a zipped representation of the resource pack and writes it to the specified destination path.
   *
   * @param dest the destination path where the zipped resource pack will be written
   */
  public void zip(final Path dest) {
    final MinecraftResourcePackWriter writer = MinecraftResourcePackWriter.minecraft();
    writer.writeToZipFile(dest, this.resourcePack);
  }
}
