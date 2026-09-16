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
package me.brandonli.mcav.bukkit.media.render;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.bukkit.utils.MainThreadRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Renders images as colored text inside a single text display entity.
 *
 * <p>Every pixel of the image becomes one character, colored with the exact color of the pixel. The text is set
 * directly on the server entity as a native component, which skips the costly conversion through Adventure for
 * every frame. The entity is only visible to the configured viewers and is never saved to the world, so a crash
 * cannot leave stray entities behind.
 *
 * <p>Because the entity is not saved, the server discards it when its chunk unloads, for example when every
 * player walked away. The renderer notices that on the next frame and spawns a new entity once the chunk is
 * loaded again, so the display comes back when the viewers return.
 */
public final class EntityRenderer extends MainThreadRenderer<Component> {

  private final EntityConfiguration configuration;

  private @Nullable TextDisplay entity;

  /**
   * Constructs a new entity renderer. Nothing is spawned until {@link #show()} is called.
   *
   * @param configuration the configuration describing the viewers, position, and size of the entity image
   * @throws NullPointerException if the configuration is null
   */
  public EntityRenderer(final EntityConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    this.configuration = configuration;
  }

  /**
   * Spawns the text display and shows it to every online viewer. May be called from any thread.
   *
   * @throws IllegalStateException if the position of the entity has no world, when called on the main thread
   */
  public void show() {
    MainThreadRenderer.runOnMainThread(this::spawnEntity);
    this.startRendering();
  }

  /**
   * Converts the image into colored text and schedules it for display. May be called from any thread.
   *
   * @param image the image to render, which is resized to the entity dimensions in place
   * @throws NullPointerException if the image is null
   */
  public void render(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    final int width = this.configuration.getEntityWidth();
    final int height = this.configuration.getEntityHeight();
    final ResizeFilter resize = new ResizeFilter(width, height);
    resize.applyFilter(image);

    final int[] pixels = image.getPixels();
    final String character = this.configuration.getCharacter();
    final Component text = ChatUtils.createChatComponent(pixels, character, width, height);
    this.submit(text);
  }

  /**
   * Removes the text display. May be called from any thread, but call it on the main thread during shutdown,
   * because a disabled plugin cannot schedule the removal anymore.
   */
  public void hide() {
    this.stopRendering();
    MainThreadRenderer.runOnMainThread(this::removeEntity);
  }

  private void spawnEntity() {
    if (this.entity != null) {
      return;
    }

    final Location configuredPosition = this.configuration.getPosition();
    final Location location = configuredPosition.clone();
    final World world = location.getWorld();
    if (world == null) {
      throw new IllegalStateException("Entity position has no world");
    }

    final TextDisplay display = world.spawn(location, TextDisplay.class, EntityRenderer::configureDisplay);
    this.showToViewers(display);
    this.entity = display;
  }

  private void showToViewers(final TextDisplay display) {
    final Plugin plugin = BukkitModule.getPlugin();
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player != null) {
        player.showEntity(plugin, display);
      }
    }
  }

  // runs before the entity is added to the world, so no viewer ever sees the default settings
  private static void configureDisplay(final TextDisplay display) {
    display.setPersistent(false);
    display.setInvulnerable(true);
    display.setSeeThrough(false);
    display.setShadowed(false);
    display.setVisibleByDefault(false);
    display.setAlignment(TextDisplay.TextAlignment.CENTER);
    display.setBillboard(Display.Billboard.VERTICAL);
    display.setBackgroundColor(Color.BLACK);
    display.setLineWidth(Integer.MAX_VALUE);
  }

  /**
   * Sets the text of the display. If the display is gone, because its chunk was unloaded, a new display is spawned
   * first, as soon as the chunk is loaded again.
   *
   * @param text the text of the frame
   */
  @Override
  protected void apply(final Component text) {
    final TextDisplay current = this.entity;
    if (current == null) {
      return;
    }

    final boolean valid = current.isValid();
    final TextDisplay display = valid ? current : this.respawnEntity();
    if (display == null) {
      return;
    }

    final CraftTextDisplay craftDisplay = (CraftTextDisplay) display;
    final net.minecraft.world.entity.Display.TextDisplay handle = craftDisplay.getHandle();
    handle.setText(text);
  }

  /**
   * Spawns a new display in place of one that is gone. Nothing is spawned while the chunk at the position is not
   * loaded, because spawning would load it again right after the server unloaded it.
   *
   * @return the new display, or null if the chunk is not loaded or the position has no world anymore
   */
  private @Nullable TextDisplay respawnEntity() {
    final Location position = this.configuration.getPosition();
    final World world = position.getWorld();
    if (world == null) {
      return null;
    }

    final int blockX = position.getBlockX();
    final int blockZ = position.getBlockZ();
    final int chunkX = blockX >> 4;
    final int chunkZ = blockZ >> 4;
    final boolean loaded = world.isChunkLoaded(chunkX, chunkZ);
    if (!loaded) {
      return null;
    }

    this.entity = null;
    this.spawnEntity();
    return this.entity;
  }

  private void removeEntity() {
    final TextDisplay display = this.entity;
    if (display == null) {
      return;
    }

    display.remove();
    this.entity = null;
  }
}
