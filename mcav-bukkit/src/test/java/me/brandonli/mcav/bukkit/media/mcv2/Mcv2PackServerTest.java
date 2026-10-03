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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.zip.ZipFile;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.HttpHosting;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link Mcv2PackServer}: one pack for every screen, which changes only when a screen of a new size starts, is
 * swapped for the players once hosted, and is offered to the viewers of a screen, including those who join later.
 */
final class Mcv2PackServerTest {

  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

  private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000ca501");

  private static final long NOW = 1_234_567_890L;

  /** How long the slot of a stopped screen stays in the pack. */
  private static final long A_MINUTE = 60_000;

  @TempDir
  Path folder;

  private FakeServer server;

  private QueuedExecutor writer;

  private final AtomicLong millis = new AtomicLong(NOW);

  private final List<PackHosting> hostings = new CopyOnWriteArrayList<>();

  private final List<Player> offered = new ArrayList<>();

  private final List<Player> refused = new ArrayList<>();

  private Mcv2PackServer packs;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.injectModule();
    this.writer = new QueuedExecutor();
    this.packs = this.packServer(this::hosting);
  }

  @AfterEach
  void stopServer() {
    this.packs.shutdown();
    this.server.close();
  }

  private Mcv2PackServer packServer(final Function<Path, PackHosting> hosting) {
    return new Mcv2PackServer(this.folder, hosting, false, this.offered::add, this.refused::add, this.writer, this.millis::get);
  }

  private PackHosting hosting(final Path zip) {
    final PackHosting hosting = mock(PackHosting.class);
    when(hosting.getZip()).thenReturn(zip);
    when(hosting.getRawUrl()).thenReturn("http://127.0.0.1:25565/mcav/" + zip.getFileName());
    this.hostings.add(hosting);
    return hosting;
  }

  private static Mcv2Configuration screen(final int width, final Collection<UUID> viewers) {
    return Mcv2ConfigurationTest.complete().viewers(viewers).video(width, 96).pageSlots(2).build();
  }

  /** Runs the ticks and the writer until neither has anything left: every pack asked for is written and served. */
  private void settle() {
    settle(this.writer, this.server);
  }

  private static void settle(final QueuedExecutor writer, final FakeServer server) {
    while (writer.drain() + server.runTasks() > 0) {
      // the writer hands the main thread what it hosted, and the main thread hands the writer what it retires
    }
  }

  private CraftPlayer online(final UUID uuid) {
    final CraftPlayer player = this.server.addPlayer(uuid);
    when(player.isOnline()).thenReturn(true);
    return player;
  }

  private static ResourcePackRequest requestSentTo(final Player player) {
    final ArgumentCaptor<ResourcePackRequest> requests = ArgumentCaptor.forClass(ResourcePackRequest.class);
    verify(player).sendResourcePacks(requests.capture());
    return requests.getValue();
  }

  private static UUID packOf(final ResourcePackRequest request) {
    return request.packs().getFirst().id();
  }

  private void load(final Player player, final UUID pack) {
    this.packs.getViewers().handleStatus(new PlayerResourcePackStatusEvent(player, pack, Status.SUCCESSFULLY_LOADED));
  }

  /** The slots of the pack hosted last, as its manifest names them: "stream id: width x height", in strip order. */
  private List<String> slotsOfTheLastPack() throws IOException {
    final Path zip = this.hostings.getLast().getZip();
    try (
      final ZipFile pack = new ZipFile(zip.toFile());
      final InputStream manifest = pack.getInputStream(pack.getEntry("mcav_mcv2.json"))
    ) {
      final JsonObject parsed = JsonParser.parseString(new String(manifest.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      final List<String> slots = new ArrayList<>();
      for (final JsonElement screen : parsed.getAsJsonArray("screens")) {
        final JsonObject slot = screen.getAsJsonObject();
        slots.add(
          slot.get("stream_id").getAsLong() + ": " + slot.get("video_width").getAsInt() + "x" + slot.get("video_height").getAsInt()
        );
      }
      return slots;
    }
  }

  @Test
  void servesTheFirstScreenOnceItsPackIsHostedAndOffersItToTheViewers() throws IOException {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();

    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of(ALICE)));

    assertEquals(1, lease.getConfiguration().getStreamId());
    assertEquals(NOW / 10, lease.getConfiguration().getFirstFrameId());
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    this.settle();
    final ResourcePackRequest request = requestSentTo(alice);
    final ResourcePackInfo pack = request.packs().getFirst();
    final Path zip = this.folder.resolve("mcav-mcv2-1.zip");
    final String sha1 = Mcv2PackServer.hash(zip, "SHA-1");
    assertEquals(sha1, pack.hash());
    assertEquals(UUID.nameUUIDFromBytes(("mcav-mcv2:" + sha1).getBytes(StandardCharsets.UTF_8)), pack.id());
    assertEquals("http://127.0.0.1:25565/mcav/mcav-mcv2-1.zip", pack.uri().toString());
    assertFalse(request.required(), "the pack is optional");
    assertFalse(request.replace(), "the pack leaves the server's other packs alone");
    assertEquals(pack.id(), this.packs.getViewers().getPackId());
    assertEquals(Mcv2Viewers.PackState.REQUESTED, this.packs.getViewers().getState(ALICE));
    assertEquals(List.of(alice), this.offered);
    verify(bob, never()).sendResourcePacks(any(ResourcePackRequest.class));
    verify(this.hostings.getFirst()).start();
    assertTrue(Files.isRegularFile(zip));
  }

  @Test
  void aScreenOfASizeThePackHasTakesItsFreeSlotWithoutChangingThePack() {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(screen(320, Set.of(ALICE)));
    this.settle();
    first.close();
    first.close();

    this.millis.addAndGet(5_000);
    final Mcv2PackServer.Lease second = this.packs.open(screen(320, Set.of(ALICE, BOB, CAROL)));

    assertEquals(0, this.writer.drain(), "the pack stays the same");
    assertEquals(1, second.getConfiguration().getStreamId());
    assertEquals((NOW + 5_000) / 10, second.getConfiguration().getFirstFrameId());
    this.server.runTasks();
    // Alice was asked already; Bob, a viewer of the new screen, is asked on the main thread; Carol is offline
    verify(alice).sendResourcePacks(any(ResourcePackRequest.class));
    requestSentTo(bob);
    assertEquals(List.of(alice, bob), this.offered);
  }

  @Test
  void aScreenClosedBeforeTheNextTickOffersNothing() {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();
    this.packs.open(screen(320, Set.of(ALICE))).close();
    this.settle();

    this.packs.open(screen(320, Set.of(BOB))).close();
    this.server.runTasks();

    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    verify(bob, never()).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void aScreenOfANewSizeGetsASlotAndThePlayersSwapThePack() throws IOException {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(screen(320, Set.of(ALICE)));
    this.settle();
    final UUID oldPack = packOf(requestSentTo(alice));
    this.load(alice, oldPack);
    assertTrue(this.packs.getViewers().isLoaded(ALICE));

    final Mcv2PackServer.Lease second = this.packs.open(screen(160, Set.of(BOB)));
    assertEquals(2, second.getConfiguration().getStreamId());
    assertTrue(this.packs.getViewers().isLoaded(ALICE), "the old pack plays until the new one is hosted");
    this.settle();

    verify(alice).removeResourcePacks(oldPack);
    final ArgumentCaptor<ResourcePackRequest> requests = ArgumentCaptor.forClass(ResourcePackRequest.class);
    verify(alice, Mockito.times(2)).sendResourcePacks(requests.capture());
    final UUID newPack = packOf(requests.getValue());
    assertNotEquals(oldPack, newPack);
    assertFalse(this.packs.getViewers().isLoaded(ALICE), "nobody has the new pack until their client says so");
    verify(this.hostings.getFirst()).shutdown();
    assertFalse(Files.exists(this.folder.resolve("mcav-mcv2-1.zip")));
    assertTrue(Files.isRegularFile(this.folder.resolve("mcav-mcv2-2.zip")));
    assertTrue(first.getConfiguration().getStreamId() != second.getConfiguration().getStreamId());
  }

  @Test
  void screensThatStartInTheSameTickChangeThePackOnce() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    this.packs.open(screen(320, Set.of(ALICE)));
    this.packs.open(screen(160, Set.of(ALICE)));

    this.settle();

    assertEquals(1, this.hostings.size());
    assertEquals("http://127.0.0.1:25565/mcav/mcav-mcv2-2.zip", requestSentTo(alice).packs().getFirst().uri().toString());
  }

  @Test
  void aPackAskedForBeforeTheOneBeforeWasWrittenIsTheOnlyOneWritten() {
    this.packs.start();
    this.packs.open(screen(320, Set.of()));
    this.server.runTasks();
    this.packs.open(screen(160, Set.of()));
    this.server.runTasks();

    this.settle();

    assertEquals(1, this.hostings.size());
    verify(this.hostings.getFirst(), never()).shutdown();
  }

  @Test
  void aPackHostedAfterANewerOneWasAskedForIsNotServed() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    this.packs.open(screen(320, Set.of(ALICE)));
    this.server.runTasks();
    this.writer.drain();
    this.packs.open(screen(160, Set.of(ALICE)));

    this.settle();

    assertEquals(2, this.hostings.size());
    verify(this.hostings.getFirst()).shutdown();
    verify(this.hostings.get(1), never()).shutdown();
    assertEquals("http://127.0.0.1:25565/mcav/mcav-mcv2-2.zip", requestSentTo(alice).packs().getFirst().uri().toString());
    verify(alice, never()).removeResourcePacks(any(UUID.class));
  }

  @Test
  void aFullPackGivesTheSlotFreedFirstANewSizeAndRefusesAScreenWhenEverySlotPlays() {
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int slot = 0; slot < Mcv2Pack.MAX_SCREENS; slot++) {
      leases.add(this.packs.open(screen(160 + 32 * slot, Set.of())));
    }
    this.settle();
    leases.get(4).close();
    leases.get(2).close();
    leases.get(6).close();

    final Mcv2PackServer.Lease fifth = this.packs.open(screen(640, Set.of()));
    final Mcv2PackServer.Lease third = this.packs.open(screen(608, Set.of()));
    final Mcv2PackServer.Lease seventh = this.packs.open(screen(576, Set.of()));

    assertEquals(5, fifth.getConfiguration().getStreamId(), "the slot freed first");
    assertEquals(3, third.getConfiguration().getStreamId());
    assertEquals(7, seventh.getConfiguration().getStreamId());
    // every slot writes its pages on maps of its own
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 32, fifth.getConfiguration().getPageMap());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 16, third.getConfiguration().getPageMap());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 48, seventh.getConfiguration().getPageMap());
    final IllegalStateException full = assertThrows(IllegalStateException.class, () -> this.packs.open(screen(544, Set.of())));
    assertEquals("Every one of the 8 MCV2 slots plays a screen", full.getMessage());
    this.settle();
    assertEquals(2, this.hostings.size(), "one pack for the eight slots, then one for the three new sizes");
  }

  @Test
  void refusesAScreenWhosePageMapsCannotReachEverySlot() {
    final Mcv2Configuration high = Mcv2ConfigurationTest.complete()
      .pageMap(Integer.MAX_VALUE - 64)
      .build();
    assertEquals(1, this.packs.open(high).getConfiguration().getStreamId());
    final Mcv2Configuration higher = Mcv2ConfigurationTest.complete()
      .pageMap(Integer.MAX_VALUE - 63)
      .build();
    assertThrows(IllegalArgumentException.class, () -> this.packs.open(higher));
  }

  @Test
  void aReshapedSlotKeepsItsNewSizeForTheNextScreenOfThatSize() {
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int slot = 0; slot < Mcv2Pack.MAX_SCREENS; slot++) {
      leases.add(this.packs.open(screen(160 + 32 * slot, Set.of())));
    }
    this.settle();
    leases.getFirst().close();
    this.packs.open(screen(640, Set.of())).close();
    this.settle();
    assertEquals(2, this.hostings.size(), "the full pack changed the free slot to the new size");
    this.packs.open(screen(640, Set.of())).close();
    this.settle();
    assertEquals(2, this.hostings.size(), "the slot kept its new size, so the pack stays");
  }

  @Test
  void offersThePackToAViewerAddedInTheGameOnceTheyChangeWorld() throws EventException {
    final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    this.packs.start();
    final EventExecutor world = this.registered(PlayerChangedWorldEvent.class);
    final Listener listener = this.listener(PlayerChangedWorldEvent.class);
    this.packs.open(screen(320, viewers));
    this.settle();
    final CraftPlayer carol = this.online(CAROL);
    viewers.add(CAROL);
    world.execute(listener, new PlayerChangedWorldEvent(carol, mock(World.class)));
    verify(carol, never()).sendResourcePacks(any(ResourcePackRequest.class));
    this.server.runTasks();
    requestSentTo(carol);
  }

  @Test
  void measuresTheLoadOnlyOfThePackAPlayerWasOffered() {
    final CraftPlayer alice = this.online(ALICE);
    final Mcv2PackServer.Lease first = this.packs.open(screen(320, Set.of(ALICE)));
    this.settle();
    requestSentTo(alice);
    first.close();
    // the next pack is for Bob's screen: Alice is not asked to load it
    this.packs.open(screen(160, Set.of(BOB)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();
    assertEquals(-1, this.packs.handleStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED)));
  }

  @Test
  void listensToThePackStatusForItselfAndItsViewersUntilShutDown() {
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      this.packs.start();
      final ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
      verify(this.server.getPluginManager(), Mockito.times(2)).registerEvent(
        eq(PlayerResourcePackStatusEvent.class),
        listeners.capture(),
        eq(EventPriority.MONITOR),
        any(EventExecutor.class),
        any(Plugin.class)
      );
      this.packs.shutdown();
      for (final Listener listener : listeners.getAllValues()) {
        handlers.verify(() -> HandlerList.unregisterAll(listener));
      }
    }
  }

  @Test
  void deletesThePackItServesWhenShutDown() {
    this.packs.open(screen(320, Set.of()));
    this.settle();
    final Path zip = this.folder.resolve("mcav-mcv2-1.zip");
    assertTrue(Files.isRegularFile(zip));
    this.packs.shutdown();
    verify(this.hostings.getFirst()).shutdown();
    assertFalse(Files.exists(zip));
  }

  @Test
  void retiresAPackANewerOneReplacedBeforeItWasServed() {
    this.packs.open(screen(320, Set.of()));
    this.server.runTasks();
    this.writer.drain();
    // the first pack is hosted and waits for the main thread, which asks for another first
    this.packs.open(screen(160, Set.of()));
    this.settle();
    verify(this.hostings.getFirst()).shutdown();
    assertFalse(Files.exists(this.folder.resolve("mcav-mcv2-1.zip")));
    assertTrue(Files.isRegularFile(this.folder.resolve("mcav-mcv2-2.zip")));
  }

  @Test
  void refusesAScreenWhoseOutlineColourIsNotThePacks() {
    this.packs.open(screen(320, Set.of()));
    final Mcv2Configuration gold = Mcv2ConfigurationTest.complete().outlineColor(NamedTextColor.GOLD).build();
    assertThrows(IllegalArgumentException.class, () -> this.packs.open(gold));
    assertThrows(NullPointerException.class, () -> this.packs.open(null));
  }

  @Test
  void aPackWhoseScreensAllClosedTakesTheOutlineColourOfTheNextScreen() {
    this.packs.open(screen(320, Set.of())).close();
    this.settle();
    final Mcv2Configuration gold = Mcv2ConfigurationTest.complete().outlineColor(NamedTextColor.GOLD).build();
    final Mcv2PackServer.Lease lease = this.packs.open(gold);
    this.settle();
    assertEquals(1, lease.getConfiguration().getStreamId(), "the gold screen has the first slot of a new pack");
    assertEquals(2, this.hostings.size(), "the pack changed for the new colour");
    final Mcv2Configuration aqua = screen(320, Set.of());
    assertThrows(IllegalArgumentException.class, () -> this.packs.open(aqua), "while the gold screen plays");
  }

  @Test
  void aScreenKeepsTheSlotOfTheSizeItLeftSoSteppingBackChangesNothing() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2Configuration full = screen(320, Set.of(ALICE));
    final Mcv2PackServer.Lease lease = this.packs.open(full);
    this.settle();
    final Mcv2Configuration requested = lease.getConfiguration();

    final Mcv2Channel smaller = lease.resize(requested.withVideo(160, 90));
    this.settle();
    final Mcv2Channel back = lease.resize(requested.withVideo(320, 96));
    final Mcv2Channel again = lease.resize(requested.withVideo(160, 90));

    assertEquals(0, this.writer.drain(), "the pack has both sizes already");
    assertEquals(2, this.hostings.size());
    final Mcv2Configuration step = smaller.getConfiguration();
    assertEquals(2, step.getStreamId());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 8, step.getPageMap());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP, back.getConfiguration().getPageMap());
    assertEquals(requested.getFirstFrameId(), step.getFirstFrameId());
    assertEquals(1, back.getConfiguration().getStreamId());
    assertEquals(2, again.getConfiguration().getStreamId());
    this.load(alice, this.packs.getViewers().getPackId());
    back.update();
    assertEquals(1, this.server.getScheduledTaskCount(), "a viewer with the pack is shown the screen at either size");
    assertThrows(NullPointerException.class, () -> lease.resize(null));
    lease.close();
    assertThrows(IllegalStateException.class, () -> lease.resize(requested));
  }

  @Test
  void aScreenInALaterSlotKeepsItsOwnPageMapsWhenItStepsToAnotherSize() {
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(screen(320, Set.of()));
    final Mcv2PackServer.Lease second = this.packs.open(screen(288, Set.of()));
    this.settle();
    final Mcv2Configuration playing = second.getConfiguration();
    assertEquals(2, playing.getStreamId());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 8, playing.getPageMap());

    // the screen's result asks for the other size with the configuration its slot gave it
    final Mcv2Channel stepped = second.resize(playing.withVideo(160, 90));
    this.settle();
    final Mcv2PackServer.Lease third = this.packs.open(screen(256, Set.of()));

    final Mcv2Configuration step = stepped.getConfiguration();
    assertEquals(3, step.getStreamId());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 16, step.getPageMap(), "the page maps of slot 3");
    assertEquals(4, third.getConfiguration().getStreamId());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 24, third.getConfiguration().getPageMap());
    final Mcv2Channel back = second.resize(playing.withVideo(288, 96));
    assertEquals(playing.getPageMap(), back.getConfiguration().getPageMap(), "the screen's own page maps at its own size");
    first.close();
  }

  @Test
  void aSizeWithoutAFreeSlotIsDitheredForEveryViewer() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int slot = 0; slot < Mcv2Pack.MAX_SCREENS; slot++) {
      leases.add(this.packs.open(screen(160 + 32 * slot, Set.of(ALICE))));
    }
    this.settle();
    this.load(alice, this.packs.getViewers().getPackId());

    final Mcv2Channel channel = leases.getFirst().resize(leases.getFirst().getConfiguration().withVideo(128, 72));

    assertEquals(0, this.writer.drain() + this.server.getScheduledTaskCount(), "the pack stays the same");
    assertEquals(Set.of(ALICE), channel.update());
    assertEquals(0, this.server.getScheduledTaskCount(), "nobody is shown a screen their client cannot decode");
  }

  @Test
  void offersThePackToAViewerWhoJoinsOrChangesWorldWhileTheScreenPlays() throws EventException {
    final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    viewers.add(ALICE);
    this.packs.start();
    final EventExecutor join = this.registered(PlayerJoinEvent.class);
    final EventExecutor world = this.registered(PlayerChangedWorldEvent.class);
    final Listener listener = this.listener(PlayerJoinEvent.class);
    final CraftPlayer alice = this.online(ALICE);
    // no pack is served yet: nothing to offer
    join.execute(listener, new PlayerJoinEvent(alice, (Component) null));
    this.server.runTasks();
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    this.packs.open(screen(320, viewers));
    this.settle();
    requestSentTo(alice);

    // Bob joins; the screen's owner adds him to its viewers, and he is asked on the next tick
    final CraftPlayer bob = this.online(BOB);
    viewers.add(BOB);
    join.execute(listener, new PlayerJoinEvent(bob, (Component) null));
    verify(bob, never()).sendResourcePacks(any(ResourcePackRequest.class));
    this.server.runTasks();
    requestSentTo(bob);

    // Carol watches nothing
    final CraftPlayer carol = this.online(CAROL);
    world.execute(listener, new PlayerChangedWorldEvent(carol, mock(World.class)));
    this.server.runTasks();
    verify(carol, never()).sendResourcePacks(any(ResourcePackRequest.class));

    // Alice declined; changing world does not ask her again
    this.packs.getViewers().handleStatus(new PlayerResourcePackStatusEvent(alice, this.packs.getViewers().getPackId(), Status.DECLINED));
    world.execute(listener, new PlayerChangedWorldEvent(alice, mock(World.class)));
    this.server.runTasks();
    verify(alice).sendResourcePacks(any(ResourcePackRequest.class));
    assertEquals(List.of(alice), this.refused);

    // a viewer who left before the next tick is not asked
    final CraftPlayer gone = this.server.addPlayer(UUID.randomUUID());
    viewers.add(gone.getUniqueId());
    join.execute(listener, new PlayerJoinEvent(gone, (Component) null));
    this.server.runTasks();
    verify(gone, never()).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void measuresHowLongAClientTookFromTheOfferToThePackLoaded() throws EventException {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();
    final EventExecutor status = this.registered(PlayerResourcePackStatusEvent.class);
    final EventExecutor quit = this.registered(PlayerQuitEvent.class);
    final Listener listener = this.listener(PlayerJoinEvent.class);
    final PlayerResourcePackStatusEvent early = new PlayerResourcePackStatusEvent(alice, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED);
    assertEquals(-1, this.packs.handleStatus(early), "no pack is served yet");
    this.packs.open(screen(320, Set.of(ALICE, BOB)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();

    this.millis.addAndGet(2_500);

    assertEquals(-1, this.packs.handleStatus(new PlayerResourcePackStatusEvent(alice, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED)));
    assertEquals(-1, this.packs.handleStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.DOWNLOADED)));
    assertEquals(2_500, this.packs.handleStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED)));
    assertEquals(-1, this.packs.handleStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED)), "measured once");
    // the registered listeners reach the same code, and a player who left is forgotten
    status.execute(listener, new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
    quit.execute(listener, new PlayerQuitEvent(bob, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertEquals(-1, this.packs.handleStatus(new PlayerResourcePackStatusEvent(bob, pack, Status.SUCCESSFULLY_LOADED)));
  }

  @Test
  void anHttpHostingStopsTheOneBeforeItWhichHoldsItsPort() {
    final List<HttpHosting> servers = new ArrayList<>();
    this.packs = this.packServer(zip -> {
      final HttpHosting hosting = mock(HttpHosting.class);
      when(hosting.getZip()).thenReturn(zip);
      when(hosting.getRawUrl()).thenReturn("http://203.0.113.7:8080");
      servers.add(hosting);
      return hosting;
    });
    this.packs.open(screen(320, Set.of()));
    this.settle();
    this.packs.open(screen(160, Set.of()));
    this.settle();

    final InOrder order = inOrder(servers.getFirst(), servers.get(1));
    order.verify(servers.getFirst()).start();
    order.verify(servers.getFirst()).shutdown();
    order.verify(servers.get(1)).start();
  }

  @Test
  void aPackThatCannotBeHostedIsNotServedAndTheNextChangeTriesAgain() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs = this.packServer(zip -> {
      if (this.hostings.isEmpty()) {
        this.hostings.add(mock(PackHosting.class));
        throw new IllegalStateException("upload failed");
      }
      return this.hosting(zip);
    });
    this.packs.open(screen(320, Set.of(ALICE)));
    this.settle();
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    assertFalse(Files.exists(this.folder.resolve("mcav-mcv2-1.zip")));

    this.packs.open(screen(160, Set.of(ALICE)));
    this.settle();

    requestSentTo(alice);
  }

  @Test
  void clearsThePacksAnEarlierRunLeftAndNothingElse() throws IOException {
    Files.writeString(this.folder.resolve("mcav-mcv2-7.zip"), "old");
    Files.writeString(this.folder.resolve("notes.txt"), "kept");

    this.packs.start();
    this.writer.drain();

    assertFalse(Files.exists(this.folder.resolve("mcav-mcv2-7.zip")));
    assertTrue(Files.exists(this.folder.resolve("notes.txt")));
  }

  @Test
  void aFolderThatCannotBeCreatedIsReportedAndThePackIsNotServed() throws IOException {
    final Path file = this.folder.resolve("taken");
    Files.writeString(file, "not a folder");
    final QueuedExecutor own = new QueuedExecutor();
    final Mcv2PackServer blocked = new Mcv2PackServer(
      file,
      this::hosting,
      false,
      this.offered::add,
      this.refused::add,
      own,
      this.millis::get
    );
    blocked.start();
    blocked.open(screen(320, Set.of()));
    settle(own, this.server);
    assertTrue(this.hostings.isEmpty());
    blocked.shutdown();
  }

  @Test
  void shutdownStopsHostingClosesTheLeasesAndServesNothingMore() {
    final CraftPlayer alice = this.online(ALICE);
    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      this.packs.start();
      this.packs.open(screen(320, Set.of(ALICE)));
      this.settle();
      final Mcv2PackServer.Lease pending = this.packs.open(screen(160, Set.of(ALICE)));
      this.server.runTasks();
      this.writer.drain();

      this.packs.shutdown();
      this.packs.shutdown();
      this.server.runTasks();

      lists.verify(() -> HandlerList.unregisterAll(any(Listener.class)), atLeastOnce());
      verify(this.hostings.getFirst()).shutdown();
      verify(this.hostings.get(1)).shutdown();
      verify(alice).sendResourcePacks(any(ResourcePackRequest.class));
      verify(alice, never()).removeResourcePacks(any(UUID.class));
      assertThrows(IllegalStateException.class, () -> pending.resize(pending.getConfiguration()));
      assertThrows(IllegalStateException.class, () -> this.packs.open(screen(320, Set.of())));
    }
  }

  @Test
  void aPackHostedWhileTheServerShutsDownIsStoppedAtOnce() {
    this.packs = this.packServer(zip -> {
      final PackHosting hosting = this.hosting(zip);
      this.packs.shutdown();
      return hosting;
    });
    this.packs.open(screen(320, Set.of()));

    this.settle();

    verify(this.hostings.getFirst()).start();
    verify(this.hostings.getFirst()).shutdown();
    assertEquals(0, this.server.getScheduledTaskCount());
  }

  @Test
  void nothingIsWrittenOrServedAfterShutdown() {
    this.packs.open(screen(320, Set.of()));
    this.server.runTasks();
    this.packs.open(screen(160, Set.of()));

    this.packs.shutdown();
    this.server.runTasks();

    assertTrue(this.hostings.isEmpty());
    assertEquals(0, this.server.getScheduledTaskCount());
  }

  @Test
  void retiresAPackHostedTooLateWhenTheShutdownCouldNotWaitForTheWriter() {
    this.packs.open(screen(320, Set.of()));
    this.server.runTasks();
    this.writer.drain();
    // the pack is hosted; the shutdown is interrupted before the writer stops it, then the main thread serves it
    this.writer.interrupt = true;
    this.packs.shutdown();
    assertTrue(Thread.interrupted());
    this.server.runTasks();
    verify(this.hostings.getFirst()).shutdown();
    assertFalse(Files.exists(this.folder.resolve("mcav-mcv2-1.zip")));
  }

  @Test
  void shutdownThatIsInterruptedKeepsTheInterrupt() {
    this.writer.interrupt = true;
    this.packs.shutdown();
    assertTrue(Thread.interrupted());
  }

  @Test
  void shutdownWithoutStartUnregistersNothing() {
    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      this.packs.shutdown();
      lists.verifyNoInteractions();
    }
  }

  @Test
  void theDefaultWriterIsAThreadOfItsOwn() throws InterruptedException {
    final AtomicReference<Thread> writerThread = new AtomicReference<>();
    final Mcv2PackServer own = new Mcv2PackServer(
      this.folder,
      zip -> {
        writerThread.set(Thread.currentThread());
        return this.hosting(zip);
      },
      true,
      this.offered::add,
      this.refused::add
    );
    try {
      own.start();
      own.open(screen(320, Set.of()));
      this.server.runTasks();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (this.hostings.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
    } finally {
      own.shutdown();
    }
    assertNotEquals(Thread.currentThread(), writerThread.get(), "pack creation must leave the calling thread");
    assertEquals(1, this.hostings.size());
    verify(this.hostings.getFirst()).shutdown();
  }

  @Test
  void rejectsMissingArguments() {
    assertThrows(NullPointerException.class, () -> new Mcv2PackServer(null, this::hosting, false, this.offered::add, this.refused::add));
    assertThrows(NullPointerException.class, () -> new Mcv2PackServer(this.folder, null, false, this.offered::add, this.refused::add));
    assertThrows(NullPointerException.class, () -> new Mcv2PackServer(this.folder, this::hosting, false, null, this.refused::add));
    assertThrows(NullPointerException.class, () -> new Mcv2PackServer(this.folder, this::hosting, false, this.offered::add, null));
  }

  @Test
  void hashesAFileAndReportsWhatItCannotHash() throws IOException {
    final Path file = this.folder.resolve("pack.zip");
    Files.writeString(file, "abc");
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Mcv2PackServer.hash(file, "SHA-1"));
    assertThrows(UncheckedIOException.class, () -> Mcv2PackServer.hash(this.folder.resolve("missing.zip"), "SHA-1"));
    assertThrows(IllegalStateException.class, () -> Mcv2PackServer.hash(file, "NO-SUCH-DIGEST"));
  }

  @Test
  void aPackThatCannotBeDeletedIsLeftForTheNextStart() throws IOException {
    this.packs = this.packServer(zip -> {
      final PackHosting hosting = this.hosting(zip);
      // a folder with a file in it cannot be deleted
      when(hosting.getZip()).thenReturn(this.folder);
      return hosting;
    });
    Files.writeString(this.folder.resolve("keep.txt"), "x");
    this.packs.open(screen(320, Set.of()));
    this.settle();
    this.packs.open(screen(160, Set.of()));
    this.settle();
    verify(this.hostings.getFirst()).shutdown();
    assertTrue(Files.isDirectory(this.folder));
  }

  @Test
  void describesTheSlotsOfAPack() {
    final Mcv2Configuration first = screen(320, Set.of()).withSlot(1, Mcv2Configuration.DEFAULT_PAGE_MAP, 0);
    final Mcv2Configuration second = screen(160, Set.of()).withSlot(2, Mcv2Configuration.DEFAULT_PAGE_MAP, 0);
    assertEquals("slots 1: 320x96, 2: 160x96", Mcv2PackServer.describe(List.of(first, second)));
  }

  private EventExecutor registered(final Class<? extends Event> type) {
    final ArgumentCaptor<EventExecutor> executors = ArgumentCaptor.forClass(EventExecutor.class);
    verify(this.server.getPluginManager(), atLeastOnce()).registerEvent(
      eq(type),
      any(Listener.class),
      eq(EventPriority.MONITOR),
      executors.capture(),
      any(Plugin.class)
    );
    // the pack server registers after its viewer tracker
    return executors.getValue();
  }

  private Listener listener(final Class<? extends Event> type) {
    final ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
    verify(this.server.getPluginManager(), atLeastOnce()).registerEvent(
      eq(type),
      listeners.capture(),
      eq(EventPriority.MONITOR),
      any(EventExecutor.class),
      any(Plugin.class)
    );
    return listeners.getValue();
  }

  /** Runs what it is given only when the test drains it, like a writer thread that has not got to it yet. */
  private static final class QueuedExecutor extends AbstractExecutorService {

    private final Deque<Runnable> queue = new ArrayDeque<>();

    private boolean isShutdown;

    private boolean interrupt;

    @Override
    public void execute(final Runnable command) {
      if (this.isShutdown) {
        throw new RejectedExecutionException("shut down");
      }
      this.queue.add(command);
    }

    int drain() {
      int ran = 0;
      for (Runnable next = this.queue.poll(); next != null; next = this.queue.poll()) {
        next.run();
        ran++;
      }
      return ran;
    }

    @Override
    public void shutdown() {
      this.isShutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      this.isShutdown = true;
      return List.copyOf(this.queue);
    }

    @Override
    public boolean isShutdown() {
      return this.isShutdown;
    }

    @Override
    public boolean isTerminated() {
      return this.isShutdown && this.queue.isEmpty();
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
      if (this.interrupt) {
        throw new InterruptedException("interrupted");
      }
      this.drain();
      return true;
    }
  }

  @Test
  void screensThatPlayOneAfterAnotherShareOneSlotOfThePack() throws IOException {
    this.packs.start();
    for (int width = 160; width <= 352; width += 32) {
      this.packs.open(screen(width, Set.of())).close();
      this.settle();
    }
    final Mcv2PackServer.Lease last = this.packs.open(screen(384, Set.of()));
    this.settle();
    assertEquals(1, last.getConfiguration().getStreamId());
    assertEquals(List.of("1: 384x96"), this.slotsOfTheLastPack(), "one screen at a time needs one slot of the pack");
  }

  @Test
  void aScreenThatStepsThroughThreeSizesKeepsOnlyTheSlotOfTheSizeItLeftLast() throws IOException {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of()));
    final Mcv2Configuration playing = lease.getConfiguration();
    lease.resize(playing.withVideo(288, 96));
    lease.resize(playing.withVideo(256, 96));
    this.settle();
    assertEquals(List.of("1: 256x96", "2: 288x96"), this.slotsOfTheLastPack(), "the first size gave its slot to the third");
    final int hosted = this.hostings.size();

    assertEquals(2, lease.resize(playing.withVideo(288, 96)).getConfiguration().getStreamId());
    this.settle();

    assertEquals(hosted, this.hostings.size(), "back at the size it left last, the pack stays");
    lease.close();
  }

  @Test
  void theSlotsOfScreensThatStoppedLeaveThePackAfterAMinute() throws IOException {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease video = this.packs.open(screen(320, Set.of(ALICE)));
    final Mcv2PackServer.Lease machine = this.packs.open(screen(288, Set.of(ALICE)));
    final Mcv2PackServer.Lease desktop = this.packs.open(screen(256, Set.of(ALICE)));
    this.settle();
    final UUID threeSlots = packOf(requestSentTo(alice));
    machine.close();
    desktop.close();

    this.millis.addAndGet(A_MINUTE - 1);
    this.server.runLaterTasks();
    this.settle();
    assertEquals(1, this.hostings.size(), "within the minute a screen of either size takes its slot without a reload");

    this.millis.addAndGet(1);
    this.server.runLaterTasks();
    this.settle();
    assertEquals(List.of("1: 320x96"), this.slotsOfTheLastPack());
    verify(alice).removeResourcePacks(threeSlots);
    assertEquals(0, this.server.runLaterTasks(), "nothing is left to take out");
    video.close();
  }

  @Test
  void thePlayersRemoveThePackAMinuteAfterTheLastScreenStopped() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of(ALICE)));
    this.settle();
    final UUID pack = packOf(requestSentTo(alice));
    this.load(alice, pack);
    lease.close();

    this.millis.addAndGet(A_MINUTE);
    this.server.runLaterTasks();
    this.settle();

    verify(alice).removeResourcePacks(pack);
    verify(this.hostings.getFirst()).shutdown();
    assertEquals(1, this.hostings.size(), "no screen plays, so no pack is written");
    assertFalse(this.packs.getViewers().isLoaded(ALICE));
    // the next screen brings a pack again
    this.packs.open(screen(320, Set.of(ALICE)));
    this.settle();
    assertEquals(2, this.hostings.size());
    verify(alice, Mockito.times(2)).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void aNewScreenOfTheSizeAPlayingScreenLeftTakesThatScreensSpare() {
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(screen(320, Set.of()));
    final Mcv2Configuration requested = first.getConfiguration();
    first.resize(requested.withVideo(160, 90));
    this.settle();
    final int hosted = this.hostings.size();
    // the first screen plays at 160 in slot 2 and keeps slot 1, at 320, as its spare; no slot of 320 is free
    final Mcv2PackServer.Lease second = this.packs.open(screen(320, Set.of()));
    this.settle();
    assertEquals(1, second.getConfiguration().getStreamId(), "the pack has the size, as the playing screen's spare");
    assertEquals(hosted, this.hostings.size(), "so the pack stays");
    // the spare is the second screen's now, so stepping back takes a slot of its own
    assertEquals(3, first.resize(requested.withVideo(320, 96)).getConfiguration().getStreamId());
    second.close();
    first.close();
  }

  @Test
  void aFullPackReshapesTheSpareLeftFirstWhereverItsSlotIs() throws IOException {
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int screen = 0; screen < Mcv2Pack.MAX_SCREENS / 2; screen++) {
      final Mcv2PackServer.Lease lease = this.packs.open(screen(320 + 32 * screen, Set.of()));
      lease.resize(lease.getConfiguration().withVideo(160 + 16 * screen, 90));
      leases.add(lease);
    }
    // the first screen steps back, so its spare is now slot 2, left last, while the spares of slots 3, 5 and 7 are older
    final Mcv2PackServer.Lease firstScreen = leases.getFirst();
    assertEquals(1, firstScreen.resize(firstScreen.getConfiguration().withVideo(320, 96)).getConfiguration().getStreamId());
    this.settle();

    final Mcv2PackServer.Lease fifth = this.packs.open(screen(608, Set.of()));

    assertEquals(3, fifth.getConfiguration().getStreamId(), "the spare left first, behind a newer one");
    this.settle();
    assertTrue(this.slotsOfTheLastPack().contains("3: 608x96"), "the slot has the new screen's size");
  }

  @Test
  void aClosedScreensSlotIsTrimmedOnTheTickItsGraceEnds() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of()));
    this.settle();
    lease.close();
    // the 60 seconds of grace are 1,200 ticks of 50 ms
    verify(this.server.getScheduler()).runTaskLater(any(Plugin.class), any(Runnable.class), eq(1_200L));
  }

  @Test
  void aTrimBeforeTheLastGraceEndsAsksAgainForWhatIsLeftOfIt() {
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(screen(320, Set.of()));
    final Mcv2PackServer.Lease second = this.packs.open(screen(288, Set.of()));
    this.settle();
    first.close();
    this.millis.addAndGet(20_000);
    second.close();
    this.millis.addAndGet(40_000);
    // the trim takes the first slot out, and the second has 20 seconds of its grace left: 400 ticks
    this.server.runLaterTasks();
    verify(this.server.getScheduler()).runTaskLater(any(Plugin.class), any(Runnable.class), eq(400L));
  }

  @Test
  void aSpareOutlivesTheTrimOfTheSlotsAroundIt() throws IOException {
    this.packs.start();
    final Mcv2PackServer.Lease playing = this.packs.open(screen(320, Set.of()));
    final Mcv2Configuration requested = playing.getConfiguration();
    playing.resize(requested.withVideo(160, 90));
    final Mcv2PackServer.Lease stopped = this.packs.open(screen(288, Set.of()));
    this.settle();
    stopped.close();

    this.millis.addAndGet(A_MINUTE);
    this.server.runLaterTasks();
    this.settle();

    assertEquals(List.of("1: 320x96", "2: 160x90"), this.slotsOfTheLastPack().stream().sorted().toList());
    assertEquals(1, playing.resize(requested.withVideo(320, 96)).getConfiguration().getStreamId(), "the spare is kept");
    playing.close();
  }

  @Test
  void aPluginBeingDisabledSchedulesNoTrim() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of()));
    this.settle();
    when(this.server.getPlugin().isEnabled()).thenReturn(false);
    lease.close();
    assertEquals(0, this.server.runLaterTasks(), "its pack server is shut down next, which takes the slots out");
  }

  @Test
  void aTrimDueAfterTheShutdownChangesNothing() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of()));
    this.settle();
    lease.close();
    this.packs.shutdown();
    final int hosted = this.hostings.size();

    this.millis.addAndGet(A_MINUTE);
    assertEquals(1, this.server.runLaterTasks(), "the trim asked for before the shutdown");
    this.settle();

    assertEquals(hosted, this.hostings.size());
  }

  @Test
  void aScreenThatStoppedBeforeItsPackWasWrittenLeavesNoPackToWithdraw() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of(ALICE)));
    // the tick asks for the pack, which the writer has not written yet when the screen stops and its slot leaves
    this.server.runTasks();
    lease.close();

    this.millis.addAndGet(A_MINUTE);
    this.server.runLaterTasks();
    this.settle();

    assertEquals(List.of(), this.hostings, "the pack asked for was out of date before it was written");
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    verify(alice, never()).removeResourcePacks(any(UUID.class));
  }

  @Test
  void aResizeToTheSizeThatPlaysChangesNothing() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(screen(320, Set.of()));
    this.settle();
    final Mcv2Configuration requested = lease.getConfiguration();
    final int hosted = this.hostings.size();

    final Mcv2Channel same = lease.resize(requested.withVideo(320, 96));
    this.settle();

    assertEquals(requested.getStreamId(), same.getConfiguration().getStreamId());
    assertEquals(hosted, this.hostings.size());
    lease.close();
  }

  @Test
  void aNewScreenOfAnotherSizeTakesTheSpareOfAPlayingScreenOnlyWhenThePackIsFull() {
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int screen = 0; screen < Mcv2Pack.MAX_SCREENS / 2; screen++) {
      final Mcv2PackServer.Lease lease = this.packs.open(screen(320 + 32 * screen, Set.of()));
      lease.resize(lease.getConfiguration().withVideo(160 + 16 * screen, 90));
      leases.add(lease);
    }
    this.settle();

    // four screens, each in the slot of its size and with the spare of the size it left: every slot is taken
    final Mcv2PackServer.Lease fifth = this.packs.open(screen(608, Set.of()));

    assertEquals(1, fifth.getConfiguration().getStreamId(), "the spare left first");
    // the first screen's spare is gone: stepping back takes the spare left next, the second screen's
    final Mcv2Configuration first = leases.getFirst().getConfiguration();
    assertEquals(3, leases.getFirst().resize(first.withVideo(320, 96)).getConfiguration().getStreamId());
  }
}
