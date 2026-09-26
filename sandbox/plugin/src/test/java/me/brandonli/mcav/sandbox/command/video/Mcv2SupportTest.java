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
package me.brandonli.mcav.sandbox.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pack;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.InjectorHosting;
import me.brandonli.mcav.sandbox.locale.Message;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class Mcv2SupportTest {

  @TempDir
  private Path folder;

  static ItemStack map(final Material material, final boolean hasId, final int id) {
    final ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(material);
    final MapMeta meta = mock(MapMeta.class);
    when(meta.hasMapId()).thenReturn(hasId);
    when(meta.getMapId()).thenReturn(id);
    when(item.getItemMeta()).thenReturn(meta);
    return item;
  }

  static ItemFrame frame(final ItemStack item) {
    final ItemFrame frame = mock(ItemFrame.class);
    when(frame.getItem()).thenReturn(item);
    return frame;
  }

  @Test
  void findsTheFrameHoldingAMap() {
    final ItemFrame wrongMap = frame(map(Material.FILLED_MAP, true, 3));
    final ItemFrame noId = frame(map(Material.FILLED_MAP, false, 7));
    final ItemFrame other = frame(map(Material.STONE, true, 7));
    final ItemStack plain = mock(ItemStack.class);
    when(plain.getType()).thenReturn(Material.FILLED_MAP);
    when(plain.getItemMeta()).thenReturn(mock(ItemMeta.class));
    final ItemFrame notAMap = frame(plain);
    final ItemFrame wanted = frame(map(Material.FILLED_MAP, true, 7));
    final World first = mock(World.class);
    when(first.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(wrongMap, noId));
    final World second = mock(World.class);
    when(second.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(other, notAMap, wanted));
    assertSame(wanted, Mcv2Support.findFrame(List.of(first, second), 7));
    assertNull(Mcv2Support.findFrame(List.of(first, second), 8));
    assertFalse(Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 3), 7));
    assertTrue(Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 7), 7));
  }

  /** Makes the mocked pack writer write the given content to the path it is asked to write. */
  private static void writing(final MockedStatic<Mcv2Pack> packs, final Mcv2Configuration configuration, final String content) {
    packs
      .when(() -> Mcv2Pack.write(eq(configuration), anyBoolean(), any()))
      .thenAnswer(invocation -> {
        final Path target = invocation.getArgument(2);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.US_ASCII);
        return null;
      });
  }

  @Test
  void writesServesAndOffersThePack() throws IOException {
    final Mcv2Support support = new Mcv2Support(this.folder);
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    final Mcv2Configuration other = mock(Mcv2Configuration.class);
    final Player player = mock(Player.class);
    final UUID uuid = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(uuid);
    final Player watching = mock(Player.class);
    final UUID watchingId = UUID.randomUUID();
    when(watching.getUniqueId()).thenReturn(watchingId);
    final InjectorHosting hosting = mock(InjectorHosting.class);
    when(hosting.getRawUrl()).thenReturn("http://127.0.0.1:25565/mcav/resourcepack_1.zip");
    final Path next = this.folder.resolve("mcv2").resolve("mcav-mcv2-next.zip");
    final Path zip = this.folder.resolve("mcv2").resolve("mcav-mcv2.zip");
    try (
      MockedStatic<Mcv2Pack> packs = Mockito.mockStatic(Mcv2Pack.class);
      MockedStatic<PackHosting> hostings = Mockito.mockStatic(PackHosting.class);
      MockedConstruction<Mcv2Viewers> trackers = Mockito.mockConstruction(Mcv2Viewers.class, (tracker, _) ->
        when(tracker.isLoaded(watchingId)).thenReturn(true)
      )
    ) {
      writing(packs, configuration, "pack");
      writing(packs, other, "other pack");
      hostings.when(() -> PackHosting.injector(zip)).thenReturn(hosting);
      final Mcv2Viewers viewers = support.offer(configuration, List.of(player, watching));
      assertSame(trackers.constructed().getFirst(), viewers);
      packs.verify(() -> Mcv2Pack.write(configuration, false, next));
      assertEquals("pack", Files.readString(zip, StandardCharsets.US_ASCII));
      assertFalse(Files.exists(next));
      verify(hosting).start();
      verify(viewers).register();
      verify(viewers).requested(uuid);
      verify(player).sendMessage(Message.MCV2_PACK.build());
      final ArgumentCaptor<ResourcePackRequest> requests = ArgumentCaptor.forClass(ResourcePackRequest.class);
      verify(player).sendResourcePacks(requests.capture());
      final ResourcePackRequest request = requests.getValue();
      assertFalse(request.required());
      assertFalse(request.replace());
      final ResourcePackInfo info = request.packs().getFirst();
      assertEquals("http://127.0.0.1:25565/mcav/resourcepack_1.zip", info.uri().toString());
      assertEquals(Mcv2Support.hash(zip, "SHA-1"), info.hash());
      assertEquals(UUID.nameUUIDFromBytes(("mcav-mcv2:" + info.hash()).getBytes(StandardCharsets.UTF_8)), info.id());
      // a player whose client loaded the pack already is not asked again
      verify(watching, never()).sendResourcePacks(any(ResourcePackRequest.class));
      // the same pack offered again is the one served: nothing restarts, and the pack is only asked for again
      assertSame(viewers, support.offer(configuration, List.of(player, watching)));
      assertFalse(Files.exists(next));
      assertEquals(1, trackers.constructed().size());
      verify(hosting).start();
      verify(hosting, never()).shutdown();
      verify(player, times(2)).sendResourcePacks(any(ResourcePackRequest.class));
      verify(watching, never()).sendResourcePacks(any(ResourcePackRequest.class));
      // a different pack replaces it, and the players are asked to remove the pack before
      final Mcv2Viewers replaced = support.offer(other, List.of(player));
      assertSame(trackers.constructed().get(1), replaced);
      verify(hosting).shutdown();
      verify(viewers).unregister();
      verify(player).removeResourcePacks(info.id());
      assertEquals("other pack", Files.readString(zip, StandardCharsets.US_ASCII));
      // once closed, the same pack is served anew under its id, which the players keep
      support.close();
      support.close();
      verify(replaced).unregister();
      support.offer(other, List.of(player));
      assertEquals(3, trackers.constructed().size());
      verify(hosting, times(3)).start();
      verify(player, times(1)).removeResourcePacks(any(UUID.class));
    }
    assertThrows(NullPointerException.class, () -> support.offer(null, List.of()));
    assertThrows(NullPointerException.class, () -> support.offer(mock(Mcv2Configuration.class), null));
    assertThrows(NullPointerException.class, () -> new Mcv2Support(null));
  }

  @Test
  void reportsTheRefusalToThePlayer() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    final InjectorHosting hosting = mock(InjectorHosting.class);
    when(hosting.getRawUrl()).thenReturn("http://localhost/pack.zip");
    try (
      MockedStatic<Mcv2Pack> packs = Mockito.mockStatic(Mcv2Pack.class);
      MockedStatic<PackHosting> hostings = Mockito.mockStatic(PackHosting.class);
      MockedConstruction<Mcv2Viewers> trackers = Mockito.mockConstruction(Mcv2Viewers.class, (_, context) -> {
        @SuppressWarnings("unchecked")
        final java.util.function.Consumer<Player> onRefused = (java.util.function.Consumer<Player>) context.arguments().get(1);
        final Player player = mock(Player.class);
        onRefused.accept(player);
        verify(player).sendMessage(Message.MCV2_REFUSED.build());
      })
    ) {
      writing(packs, configuration, "pack");
      hostings.when(() -> PackHosting.injector(any())).thenReturn(hosting);
      new Mcv2Support(this.folder).offer(configuration, List.of());
      assertEquals(1, trackers.constructed().size());
    }
  }

  @Test
  void movesAndDeletesPacks() throws IOException {
    final Path missing = this.folder.resolve("missing.zip");
    final Path target = this.folder.resolve("pack.zip");
    assertThrows(UncheckedIOException.class, () -> Mcv2Support.move(missing, target));
    final Path full = this.folder.resolve("full");
    Files.createDirectories(full.resolve("inside"));
    // a folder that is not empty cannot be deleted; that is not an error
    Mcv2Support.deleteQuietly(full);
    assertTrue(Files.exists(full));
    final Path file = this.folder.resolve("file.zip");
    Files.writeString(file, "x", StandardCharsets.US_ASCII);
    Mcv2Support.deleteQuietly(file);
    assertFalse(Files.exists(file));
  }

  @Test
  void hashesAFile() throws IOException {
    final Path file = this.folder.resolve("abc.txt");
    Files.writeString(file, "abc", StandardCharsets.US_ASCII);
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Mcv2Support.hash(file, "SHA-1"));
    assertThrows(IllegalStateException.class, () -> Mcv2Support.hash(file, "NO-SUCH-DIGEST"));
    assertThrows(UncheckedIOException.class, () -> Mcv2Support.hash(this.folder.resolve("missing"), "SHA-1"));
    assertEquals("mcav.mcv2.debugView", Mcv2Support.DEBUG_VIEW);
  }
}
