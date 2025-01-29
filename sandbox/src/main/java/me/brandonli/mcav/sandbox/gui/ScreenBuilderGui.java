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
package me.brandonli.mcav.sandbox.gui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.components.GuiContainer;
import dev.triumphteam.gui.components.InteractionModifier;
import dev.triumphteam.gui.components.InventoryProvider;
import dev.triumphteam.gui.components.util.Legacy;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import me.brandonli.mcav.sandbox.utils.JsonUtils;
import me.brandonli.mcav.sandbox.utils.MapUtils;
import me.brandonli.mcav.sandbox.utils.SkullUtils;
import me.brandonli.mcav.sandbox.utils.mutable.MutableInt;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class ScreenBuilderGui extends Gui {

  private static final String INCREASE_BASE64;
  private static final String DECREASE_BASE64;
  private static final InventoryProvider.Chest INVENTORY_PROVIDER;

  static {
    try {
      final Map<String, String> map = JsonUtils.toMapFromResource("heads.json");
      INCREASE_BASE64 = requireNonNull(map.get("INCREASE_ARROW"));
      DECREASE_BASE64 = requireNonNull(map.get("DECREASE_ARROW"));
      INVENTORY_PROVIDER = (title, owner, rows) -> Bukkit.createInventory(owner, rows, Legacy.SERIALIZER.serialize(title));
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  private final Player viewer;
  private final MutableInt width;
  private final MutableInt height;
  private final MutableInt id;
  private Material material;

  public ScreenBuilderGui(final Player player) {
    super(new GuiContainer.Chest(text(""), INVENTORY_PROVIDER, 5), InteractionModifier.VALUES);
    this.material = Material.OAK_PLANKS;
    this.viewer = player;
    this.width = new MutableInt(5);
    this.height = new MutableInt(5);
    this.id = new MutableInt(0);
    this.initialize();
    this.open(player);
  }

  // ____________________________
  // │__│__│__│__│__│__│__│__│__│
  // │__│XX│XX│XX│__│__│__│__│__│
  // │__│__│__│__│__│XX│__│XX│__│
  // │__│XX│XX│XX│__│__│__│__│__│
  // │__│__│__│__│__│__│__│__│__│
  // ‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾‾

  private void initialize() {
    this.setItem(2, 2, this.getGuiItem(this.getIncreaseArrow("Block Width"), this.width, true));
    this.setItem(4, 2, this.getGuiItem(this.getDecreaseArrow("Block Width"), this.width, false));
    this.setItem(2, 3, this.getGuiItem(this.getIncreaseArrow("Block Height"), this.height, true));
    this.setItem(4, 3, this.getGuiItem(this.getDecreaseArrow("Block Height"), this.height, false));
    this.setItem(2, 4, this.getGuiItem(this.getIncreaseArrow("Map ID"), this.id, true));
    this.setItem(4, 4, this.getGuiItem(this.getDecreaseArrow("Map ID"), this.id, false));
    this.setItem(3, 6, this.getMaterialItem());
    this.setItem(3, 8, this.getBuildScreenItem());
    this.update();
  }

  private GuiItem getBuildScreenItem() {
    return ItemBuilder.from(Material.LIME_STAINED_GLASS_PANE).name(text("Build Screen", GREEN)).asGuiItem(this::handleBuildScreen);
  }

  private void handleBuildScreen(final InventoryClickEvent event) {
    this.viewer.closeInventory();
    MapUtils.buildMapScreen(this.viewer, this.material, this.width.getNumber(), this.height.getNumber(), 0);
    final Location location = requireNonNull(this.viewer.getLocation());
    this.viewer.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 10, 1);
  }

  private GuiItem getGuiItem(final ItemStack stack, final MutableInt update, final boolean add) {
    return new GuiItem(stack, event -> this.mutateValue(update, add));
  }

  private void mutateValue(final MutableInt update, final boolean add) {
    if (add) {
      update.increment();
    } else {
      update.decrement();
    }
    this.update();
  }

  @Override
  public void update() {
    super.update();
    this.setItem(3, 2, this.getWidthItem());
    this.setItem(3, 4, this.getHeightItem());
    this.setItem(3, 6, this.getMaterialItem());
  }

  public GuiItem getMaterialItem() {
    return ItemBuilder.from(this.material)
      .name(join(noSeparators(), text("Material - ", GOLD), text(this.material.toString(), AQUA)))
      .asGuiItem(this::handleMaterial);
  }

  private void handleMaterial(final InventoryClickEvent event) {
    final ItemStack stack = event.getCursor();
    if (stack == null || stack.getType() == Material.AIR) {
      return;
    }
    this.material = stack.getType();
    final Component name = join(noSeparators(), text("Material - ", GOLD), text(this.material.toString(), AQUA));
    final GuiItem newStack = ItemBuilder.from(this.material).name(name).asGuiItem();
    this.setItem(3, 8, newStack);
    this.update();
  }

  public GuiItem getWidthItem() {
    return ItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE)
      .name(text("Screen Width (%d Blocks)".formatted(this.width.getNumber()), GOLD))
      .asGuiItem();
  }

  public GuiItem getHeightItem() {
    return ItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE)
      .name(text("Screen Height (%d Blocks)".formatted(this.height.getNumber()), GOLD))
      .asGuiItem();
  }

  private ItemStack getIncreaseArrow(final String data) {
    return ItemBuilder.from(SkullUtils.getSkull(INCREASE_BASE64)).name(text("%s (+1)".formatted(data), GREEN)).build();
  }

  private ItemStack getDecreaseArrow(final String data) {
    return ItemBuilder.from(SkullUtils.getSkull(DECREASE_BASE64)).name(text("%s (-1)".formatted(data), RED)).build();
  }
}
