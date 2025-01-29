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
package me.brandonli.mcav.bukkit.resourcepack;

import static net.kyori.adventure.key.Key.key;

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import net.kyori.adventure.key.Key;
import org.intellij.lang.annotations.Subst;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.base.Writable;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackWriter;
import team.unnamed.creative.sound.Sound;
import team.unnamed.creative.sound.SoundEntry;
import team.unnamed.creative.sound.SoundEvent;

/**
 * Represents a simple class for creating and managing resource packs.
 */
public final class SimpleResourcePack {

  private final ResourcePack resourcePack;

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
   * Adds a sound to the resource pack using a raw key and a file path.
   * The sound will be registered with the specified key and associated with the provided file path.
   *
   * @param raw  the raw key for the sound
   * @param path the path to the sound file
   */
  public void sound(@Subst("mcav:audio") final String raw, final Path path) {
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
   * Adds an external file to the resource pack at the specified path.
   * The file will be treated as an unknown file and added to the resource pack.
   *
   * @param path the path where the file will be added in the resource pack
   * @param file the file to be added
   */
  public void external(final String path, final Path file) {
    Preconditions.checkNotNull(path);
    Preconditions.checkNotNull(file);
    this.resourcePack.unknownFile(path, Writable.path(file));
  }

  /**
   * Adds the pack.mcmeta entry to the resource pack with the specified format and description.
   *
   * @param format      the metadata format
   * @param description the description of the resource pack
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
