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
import me.brandonli.mcav.bukkit.testing.LogCapture;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Level;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class Mcv2PackServerTest {

  private static final String SECOND_PACK_URL = "http://127.0.0.1:25565/mcav/mcav-mcv2-2.zip";

  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

  private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000ca501");

  private static final long NOW = 1_234_567_890L;

  private static final int SCREEN_WIDTH = 320;
  private static final int SCREEN_HEIGHT = 96;
  private static final int SMALL_SCREEN_WIDTH = 160;
  private static final int SMALL_SCREEN_HEIGHT = 90;
  private static final int STEPPED_SCREEN_WIDTH = 288;
  private static final int LARGE_SCREEN_WIDTH = 640;
  private static final int SPARE_SCREEN_WIDTH = 608;
  private static final int ALTERNATE_SCREEN_WIDTH = 256;

  private static final String MANIFEST_ENTRY = "mcav_mcv2.json";
  private static final String SIZE_SEPARATOR = "x";
  private static final String FIRST_SLOT_DESCRIPTION = "1: 320x96";

  private static final String FIRST_PACK_FILE_NAME = "mcav-mcv2-1.zip";
  private static final String SECOND_PACK_FILE_NAME = "mcav-mcv2-2.zip";
  private static final String OLD_PACK_FILE_NAME = "mcav-mcv2-7.zip";
  private static final String PACK_FILE_NAME = "pack.zip";
  private static final String MISSING_PACK_FILE_NAME = "missing.zip";
  private static final String NOTES_FILE_NAME = "notes.txt";
  private static final String KEPT_FILE_NAME = "keep.txt";
  private static final String BLOCKING_FILE_NAME = "taken";

  private static final String HASH_ALGORITHM = "SHA-1";

  private static final String PACK_UNCHANGED_MESSAGE = "the pack stays the same";

  private static final String ALICE_NAME = "Alice";

  private static final long A_MINUTE = 60_000;

  @TempDir
  Path folder;

  private FakeServer server;

  private QueuedExecutor writer;

  private final AtomicLong milliseconds = new AtomicLong(NOW);

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
    return new Mcv2PackServer(
      this.folder,
      hosting,
      Mcv2Pack.DebugView.HIDDEN,
      this.offered::add,
      this.refused::add,
      this.writer,
      this.milliseconds::get
    );
  }

  private PackHosting hosting(final Path zip) {
    final PackHosting hosting = mock(PackHosting.class);
    when(hosting.getZip()).thenReturn(zip);
    when(hosting.getRawUrl()).thenReturn("http://127.0.0.1:25565/mcav/%s".formatted(zip.getFileName()));
    this.hostings.add(hosting);
    return hosting;
  }

  private Mcv2Configuration screen(final int width, final Collection<UUID> viewers) {
    final Mcv2ConfigurationBuilder builder = this.screenBuilder(width, viewers);
    return builder.build();
  }

  private Mcv2ConfigurationBuilder screenBuilder(final int width, final Collection<UUID> viewers) {
    return Mcv2ConfigurationTest.complete().viewers(viewers).video(width, SCREEN_HEIGHT).pageSlots(2);
  }

  private void settle() {
    this.settle(this.writer, this.server);
  }

  private void settle(final QueuedExecutor writer, final FakeServer server) {
    while (writer.drain() + server.runTasks() > 0) {
      // the writer hands the main thread what it hosted, and the main thread hands the writer what it retires
    }
  }

  private CraftPlayer online(final UUID uuid) {
    final CraftPlayer player = this.server.addPlayer(uuid);
    when(player.isOnline()).thenReturn(true);
    return player;
  }

  private ResourcePackRequest requestSentTo(final Player player) {
    final ArgumentCaptor<ResourcePackRequest> requests = ArgumentCaptor.forClass(ResourcePackRequest.class);
    verify(player).sendResourcePacks(requests.capture());

    return requests.getValue();
  }

  private UUID packOf(final ResourcePackRequest request) {
    return request.packs().getFirst().id();
  }

  private void load(final Player player, final UUID pack) {
    this.packs.getViewers().onResourcePackStatus(new PlayerResourcePackStatusEvent(player, pack, Status.SUCCESSFULLY_LOADED));
  }

  private List<String> slotsOfTheLastPack() throws IOException {
    final Path zip = this.hostings.getLast().getZip();
    try (final ZipFile pack = new ZipFile(zip.toFile()); final InputStream manifest = pack.getInputStream(pack.getEntry(MANIFEST_ENTRY))) {
      final JsonObject parsed = JsonParser.parseString(new String(manifest.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      final List<String> slots = new ArrayList<>();
      for (final JsonElement screen : parsed.getAsJsonArray("screens")) {
        final JsonObject slot = screen.getAsJsonObject();
        final String slotDescription = "%s: %s%s%s".formatted(
          slot.get("stream_id").getAsLong(),
          slot.get("video_width").getAsInt(),
          SIZE_SEPARATOR,
          slot.get("video_height").getAsInt()
        );
        slots.add(slotDescription);
      }
      return slots;
    }
  }

  @Test
  void shouldServeTheFirstScreenOnceItsPackIsHostedAndOfferItToTheViewers() throws IOException {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();

    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));

    final long streamId = lease.getConfiguration().getStreamId();
    assertEquals(1, streamId);

    final long expectedFirstFrameId = NOW / 10;
    final long firstFrameId = lease.getConfiguration().getFirstFrameId();
    assertEquals(expectedFirstFrameId, firstFrameId);
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));

    this.settle();
    final ResourcePackRequest request = this.requestSentTo(alice);
    final ResourcePackInfo pack = request.packs().getFirst();
    final Path zip = this.folder.resolve(FIRST_PACK_FILE_NAME);
    final String sha1 = Mcv2PackServer.hash(zip, HASH_ALGORITHM);
    final String packHash = pack.hash();
    assertEquals(sha1, packHash);

    final UUID expectedId = UUID.nameUUIDFromBytes("mcav-mcv2:%s".formatted(sha1).getBytes(StandardCharsets.UTF_8));
    final UUID id = pack.id();
    assertEquals(expectedId, id);

    final String uri = pack.uri().toString();
    assertEquals("http://127.0.0.1:25565/mcav/mcav-mcv2-1.zip", uri);

    final boolean required = request.required();
    assertFalse(required, "the pack is optional");

    final boolean replacesOtherPacks = request.replace();
    assertFalse(replacesOtherPacks, "the pack leaves the server's other packs alone");

    final UUID expectedPackId = pack.id();
    final UUID servedPackId = this.packs.getViewers().getPackId();
    assertEquals(expectedPackId, servedPackId);

    final Mcv2Viewers.PackState state = this.packs.getViewers().getState(ALICE).orElseThrow();
    assertEquals(Mcv2Viewers.PackState.REQUESTED, state);

    final List<Player> expectedOffered = List.of(alice);
    assertEquals(expectedOffered, this.offered);
    verify(bob, never()).sendResourcePacks(any(ResourcePackRequest.class));
    verify(this.hostings.getFirst()).start();

    final boolean written = Files.isRegularFile(zip);
    assertTrue(written);
  }

  @Test
  void shouldGiveAScreenOfASizeThePackHasItsFreeSlotWithoutChangingThePack() {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    first.close();
    first.close();

    this.milliseconds.addAndGet(5_000);
    final Mcv2PackServer.Lease second = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE, BOB, CAROL)));

    final int drained = this.writer.drain();
    assertEquals(0, drained, PACK_UNCHANGED_MESSAGE);

    final long streamId = second.getConfiguration().getStreamId();
    assertEquals(1, streamId);

    final long expectedFirstFrameId = (NOW + 5_000) / 10;
    final long firstFrameId = second.getConfiguration().getFirstFrameId();
    assertEquals(expectedFirstFrameId, firstFrameId);

    this.server.runTasks();
    // Alice was asked already; Bob, a viewer of the new screen, is asked on the main thread; Carol is offline
    verify(alice).sendResourcePacks(any(ResourcePackRequest.class));

    this.requestSentTo(bob);
    final List<Player> expectedOffered = List.of(alice, bob);
    assertEquals(expectedOffered, this.offered);
  }

  @Test
  void shouldOfferNothingWhenAScreenClosesBeforeTheNextTick() {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE))).close();
    this.settle();

    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(BOB))).close();
    this.server.runTasks();

    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    verify(bob, never()).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void shouldGiveAScreenOfANewSizeASlotAndSwapThePackForThePlayers() throws IOException {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final UUID oldPack = this.packOf(this.requestSentTo(alice));
    this.load(alice, oldPack);
    final boolean loaded = this.packs.getViewers().isLoaded(ALICE);
    assertTrue(loaded);

    final Mcv2PackServer.Lease second = this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of(BOB)));
    final long secondStreamId = second.getConfiguration().getStreamId();
    assertEquals(2, secondStreamId);

    final boolean stillLoaded = this.packs.getViewers().isLoaded(ALICE);
    assertTrue(stillLoaded, "the old pack plays until the new one is hosted");

    this.settle();

    verify(alice).removeResourcePacks(oldPack);

    final ArgumentCaptor<ResourcePackRequest> requests = ArgumentCaptor.forClass(ResourcePackRequest.class);
    verify(alice, Mockito.times(2)).sendResourcePacks(requests.capture());

    final UUID newPack = this.packOf(requests.getValue());
    assertNotEquals(oldPack, newPack);

    final boolean loadedAfterSwap = this.packs.getViewers().isLoaded(ALICE);
    assertFalse(loadedAfterSwap, "nobody has the new pack until their client says so");
    verify(this.hostings.getFirst()).shutdown();

    final boolean firstPackExists = Files.exists(this.folder.resolve(FIRST_PACK_FILE_NAME));
    assertFalse(firstPackExists);

    final boolean secondPackWritten = Files.isRegularFile(this.folder.resolve(SECOND_PACK_FILE_NAME));
    assertTrue(secondPackWritten);

    final boolean differentStreams = first.getConfiguration().getStreamId() != second.getConfiguration().getStreamId();
    assertTrue(differentStreams);
  }

  @Test
  void shouldChangeThePackOnceWhenScreensStartInTheSameTick() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of(ALICE)));

    this.settle();

    final int hostingCount = this.hostings.size();
    assertEquals(1, hostingCount);

    final String uri = this.requestSentTo(alice).packs().getFirst().uri().toString();
    assertEquals(SECOND_PACK_URL, uri);
  }

  @Test
  void shouldWriteOnlyAPackAskedForBeforeTheOneBeforeWasWritten() {
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.server.runTasks();
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of()));
    this.server.runTasks();

    this.settle();

    final int hostingCount = this.hostings.size();
    assertEquals(1, hostingCount);
    verify(this.hostings.getFirst(), never()).shutdown();
  }

  @Test
  void shouldNotServeAPackHostedAfterANewerOneWasAskedFor() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.server.runTasks();
    this.writer.drain();
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of(ALICE)));

    this.settle();

    final int hostingCount = this.hostings.size();
    assertEquals(2, hostingCount);
    verify(this.hostings.getFirst()).shutdown();
    verify(this.hostings.get(1), never()).shutdown();

    final String uri = this.requestSentTo(alice).packs().getFirst().uri().toString();
    assertEquals(SECOND_PACK_URL, uri);
    verify(alice, never()).removeResourcePacks(any(UUID.class));
  }

  @Test
  void shouldGiveANewSizeTheSlotFreedFirstInAFullPackAndRefuseAScreenWhenEverySlotPlays() {
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int slot = 0; slot < Mcv2Pack.MAXIMUM_SCREENS; slot++) {
      leases.add(this.packs.open(this.screen(SMALL_SCREEN_WIDTH + 32 * slot, Set.of())));
    }

    this.settle();
    leases.get(4).close();
    leases.get(2).close();
    leases.get(6).close();

    final Mcv2PackServer.Lease fifth = this.packs.open(this.screen(LARGE_SCREEN_WIDTH, Set.of()));
    final Mcv2PackServer.Lease third = this.packs.open(this.screen(SPARE_SCREEN_WIDTH, Set.of()));
    final Mcv2PackServer.Lease seventh = this.packs.open(this.screen(576, Set.of()));

    final long fifthStreamId = fifth.getConfiguration().getStreamId();
    assertEquals(5, fifthStreamId, "the slot freed first");

    final long thirdStreamId = third.getConfiguration().getStreamId();
    assertEquals(3, thirdStreamId);

    final long seventhStreamId = seventh.getConfiguration().getStreamId();
    assertEquals(7, seventhStreamId);
    // every slot writes its pages on maps of its own
    final int expectedFifthPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 32;
    final int fifthPageMap = fifth.getConfiguration().getPageMap();
    assertEquals(expectedFifthPageMap, fifthPageMap);

    final int expectedThirdPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 16;
    final int thirdPageMap = third.getConfiguration().getPageMap();
    assertEquals(expectedThirdPageMap, thirdPageMap);

    final int expectedSeventhPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 48;
    final int seventhPageMap = seventh.getConfiguration().getPageMap();
    assertEquals(expectedSeventhPageMap, seventhPageMap);

    final IllegalStateException full = assertThrows(IllegalStateException.class, () -> this.packs.open(this.screen(544, Set.of())));
    final String message = full.getMessage();
    assertEquals("Every one of the 8 MCV2 slots plays a screen!", message);

    this.settle();
    final int hostingCount = this.hostings.size();
    assertEquals(2, hostingCount, "one pack for the eight slots, then one for the three new sizes");
  }

  @Test
  void shouldRefuseAScreenWhosePageMapsCannotReachEverySlot() {
    final Mcv2Configuration high = Mcv2ConfigurationTest.complete()
      .pageMap(Integer.MAX_VALUE - 64)
      .build();
    final long streamId = this.packs.open(high).getConfiguration().getStreamId();
    assertEquals(1, streamId);

    final Mcv2Configuration higher = Mcv2ConfigurationTest.complete()
      .pageMap(Integer.MAX_VALUE - 63)
      .build();
    assertThrows(IllegalArgumentException.class, () -> this.packs.open(higher));
  }

  @Test
  void shouldKeepTheNewSizeOfAReshapedSlotForTheNextScreenOfThatSize() {
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int slot = 0; slot < Mcv2Pack.MAXIMUM_SCREENS; slot++) {
      leases.add(this.packs.open(this.screen(SMALL_SCREEN_WIDTH + 32 * slot, Set.of())));
    }

    this.settle();
    leases.getFirst().close();
    this.packs.open(this.screen(LARGE_SCREEN_WIDTH, Set.of())).close();
    this.settle();
    final int hostingCountAfterReshape = this.hostings.size();
    assertEquals(2, hostingCountAfterReshape, "the full pack changed the free slot to the new size");

    this.packs.open(this.screen(LARGE_SCREEN_WIDTH, Set.of())).close();
    this.settle();
    final int hostingCountAfterSameSize = this.hostings.size();
    assertEquals(2, hostingCountAfterSameSize, "the slot kept its new size, so the pack stays");
  }

  @Test
  void shouldOfferThePackToAViewerAddedInTheGameOnceTheyChangeWorld() throws EventException {
    final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    this.packs.start();
    final EventExecutor world = this.registered(PlayerChangedWorldEvent.class);
    final Listener listener = this.listener(PlayerChangedWorldEvent.class);
    this.packs.open(this.screen(SCREEN_WIDTH, viewers));
    this.settle();
    final CraftPlayer carol = this.online(CAROL);
    viewers.add(CAROL);
    world.execute(listener, new PlayerChangedWorldEvent(carol, mock(World.class)));
    verify(carol, never()).sendResourcePacks(any(ResourcePackRequest.class));

    this.server.runTasks();
    this.requestSentTo(carol);
  }

  @Test
  void shouldMeasureTheLoadOnlyOfThePackAPlayerWasOffered() {
    final CraftPlayer alice = this.online(ALICE);
    final Mcv2PackServer.Lease first = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    this.requestSentTo(alice);
    first.close();
    // the next pack is for Bob's screen: Alice is not asked to load it
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of(BOB)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();
    final long loadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
    assertEquals(-1, loadTime);
  }

  @Test
  void shouldListenToThePackStatusForItselfAndItsViewersUntilShutDown() {
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
  void shouldDeleteThePackItServesWhenShutDown() {
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    final Path zip = this.folder.resolve(FIRST_PACK_FILE_NAME);
    final boolean written = Files.isRegularFile(zip);
    assertTrue(written);

    this.packs.shutdown();
    verify(this.hostings.getFirst()).shutdown();

    final boolean exists = Files.exists(zip);
    assertFalse(exists);
  }

  @Test
  void shouldRetireAPackANewerOneReplacedBeforeItWasServed() {
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.server.runTasks();
    this.writer.drain();
    // the first pack is hosted and waits for the main thread, which asks for another first
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of()));
    this.settle();
    verify(this.hostings.getFirst()).shutdown();

    final boolean firstPackExists = Files.exists(this.folder.resolve(FIRST_PACK_FILE_NAME));
    assertFalse(firstPackExists);

    final boolean secondPackWritten = Files.isRegularFile(this.folder.resolve(SECOND_PACK_FILE_NAME));
    assertTrue(secondPackWritten);
  }

  @Test
  void shouldRefuseAScreenWhoseOutlineColorIsNotThePacks() {
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    final Mcv2Configuration gold = Mcv2ConfigurationTest.complete().outlineColor(NamedTextColor.GOLD).build();
    assertThrows(IllegalArgumentException.class, () -> this.packs.open(gold));
    assertThrows(NullPointerException.class, () -> this.packs.open(null));
  }

  @Test
  void shouldGiveAPackWhoseScreensAllClosedTheOutlineColorOfTheNextScreen() {
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of())).close();
    this.settle();
    final Mcv2ConfigurationBuilder goldBuilder = this.screenBuilder(SCREEN_WIDTH, Set.of());
    final Mcv2Configuration gold = goldBuilder.outlineColor(NamedTextColor.GOLD).build();
    final Mcv2PackServer.Lease lease = this.packs.open(gold);
    this.settle();
    final long streamId = lease.getConfiguration().getStreamId();
    assertEquals(1, streamId, "the gold screen has the first slot of a new pack");

    final int hostingCount = this.hostings.size();
    assertEquals(2, hostingCount, "the pack changed for the new color");

    final Mcv2Configuration aqua = this.screen(SCREEN_WIDTH, Set.of());
    assertThrows(IllegalArgumentException.class, () -> this.packs.open(aqua), "while the gold screen plays");
  }

  @Test
  void shouldKeepTheSlotOfTheSizeAScreenLeftSoSteppingBackChangesNothing() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2Configuration full = this.screen(SCREEN_WIDTH, Set.of(ALICE));
    final Mcv2PackServer.Lease lease = this.packs.open(full);
    this.settle();
    final Mcv2Configuration requested = lease.getConfiguration();

    final Mcv2Channel smaller = lease.resize(requested.withVideo(SMALL_SCREEN_WIDTH, SMALL_SCREEN_HEIGHT));
    this.settle();
    final Mcv2Channel back = lease.resize(requested.withVideo(SCREEN_WIDTH, SCREEN_HEIGHT));
    final Mcv2Channel again = lease.resize(requested.withVideo(SMALL_SCREEN_WIDTH, SMALL_SCREEN_HEIGHT));

    final int drained = this.writer.drain();
    assertEquals(0, drained, "the pack has both sizes already");

    final int hostingCount = this.hostings.size();
    assertEquals(2, hostingCount);

    final Mcv2Configuration step = smaller.getConfiguration();
    final long stepStreamId = step.getStreamId();
    assertEquals(2, stepStreamId);

    final int expectedStepPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 8;
    final int stepPageMap = step.getPageMap();
    assertEquals(expectedStepPageMap, stepPageMap);

    final int backPageMap = back.getConfiguration().getPageMap();
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP, backPageMap);

    final long expectedFirstFrameId = requested.getFirstFrameId();
    final long stepFirstFrameId = step.getFirstFrameId();
    assertEquals(expectedFirstFrameId, stepFirstFrameId);

    final long backStreamId = back.getConfiguration().getStreamId();
    assertEquals(1, backStreamId);

    final long againStreamId = again.getConfiguration().getStreamId();
    assertEquals(2, againStreamId);

    this.load(alice, this.packs.getViewers().getPackId());
    back.update();
    final int scheduledTaskCount = this.server.getScheduledTaskCount();
    assertEquals(1, scheduledTaskCount, "a viewer with the pack is shown the screen at either size");
    assertThrows(NullPointerException.class, () -> lease.resize(null));

    lease.close();
    assertThrows(IllegalStateException.class, () -> lease.resize(requested));
  }

  @Test
  void shouldKeepThePageMapsOfAScreenInALaterSlotWhenItStepsToAnotherSize() {
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    final Mcv2PackServer.Lease second = this.packs.open(this.screen(STEPPED_SCREEN_WIDTH, Set.of()));
    this.settle();
    final Mcv2Configuration playing = second.getConfiguration();
    final long playingStreamId = playing.getStreamId();
    assertEquals(2, playingStreamId);

    final int expectedPlayingPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 8;
    final int playingPageMap = playing.getPageMap();
    assertEquals(expectedPlayingPageMap, playingPageMap);

    // the screen's result asks for the other size with the configuration its slot gave it
    final Mcv2Channel stepped = second.resize(playing.withVideo(SMALL_SCREEN_WIDTH, SMALL_SCREEN_HEIGHT));
    this.settle();
    final Mcv2PackServer.Lease third = this.packs.open(this.screen(ALTERNATE_SCREEN_WIDTH, Set.of()));

    final Mcv2Configuration step = stepped.getConfiguration();
    final long stepStreamId = step.getStreamId();
    assertEquals(3, stepStreamId);

    final int expectedStepPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 16;
    final int stepPageMap = step.getPageMap();
    assertEquals(expectedStepPageMap, stepPageMap, "the page maps of slot 3");

    final long thirdStreamId = third.getConfiguration().getStreamId();
    assertEquals(4, thirdStreamId);

    final int expectedThirdPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 24;
    final int thirdPageMap = third.getConfiguration().getPageMap();
    assertEquals(expectedThirdPageMap, thirdPageMap);

    final Mcv2Channel back = second.resize(playing.withVideo(STEPPED_SCREEN_WIDTH, SCREEN_HEIGHT));
    final int expectedBackPageMap = playing.getPageMap();
    final int backPageMap = back.getConfiguration().getPageMap();
    assertEquals(expectedBackPageMap, backPageMap, "the screen's own page maps at its own size");

    first.close();
  }

  @Test
  void shouldDitherASizeWithoutAFreeSlotForEveryViewer() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int slot = 0; slot < Mcv2Pack.MAXIMUM_SCREENS; slot++) {
      leases.add(this.packs.open(this.screen(SMALL_SCREEN_WIDTH + 32 * slot, Set.of(ALICE))));
    }

    this.settle();
    this.load(alice, this.packs.getViewers().getPackId());

    final Mcv2Channel channel = leases.getFirst().resize(leases.getFirst().getConfiguration().withVideo(128, 72));

    final int pendingWork = this.writer.drain() + this.server.getScheduledTaskCount();
    assertEquals(0, pendingWork, PACK_UNCHANGED_MESSAGE);

    final Set<UUID> expectedDithered = Set.of(ALICE);
    final Set<UUID> dithered = channel.update();
    assertEquals(expectedDithered, dithered);

    final int scheduledTaskCount = this.server.getScheduledTaskCount();
    assertEquals(0, scheduledTaskCount, "nobody is shown a screen their client cannot decode");
  }

  @Test
  void shouldOfferThePackToAViewerWhoJoinsOrChangesWorldWhileTheScreenPlays() throws EventException {
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

    this.packs.open(this.screen(SCREEN_WIDTH, viewers));
    this.settle();
    this.requestSentTo(alice);

    // Bob joins; the screen's owner adds him to its viewers, and he is asked on the next tick
    final CraftPlayer bob = this.online(BOB);
    viewers.add(BOB);
    join.execute(listener, new PlayerJoinEvent(bob, (Component) null));
    verify(bob, never()).sendResourcePacks(any(ResourcePackRequest.class));

    this.server.runTasks();
    this.requestSentTo(bob);

    // Carol watches nothing
    final CraftPlayer carol = this.online(CAROL);
    world.execute(listener, new PlayerChangedWorldEvent(carol, mock(World.class)));
    this.server.runTasks();
    verify(carol, never()).sendResourcePacks(any(ResourcePackRequest.class));

    // Alice declined; changing world does not ask her again
    this.packs
      .getViewers()
      .onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, this.packs.getViewers().getPackId(), Status.DECLINED));
    world.execute(listener, new PlayerChangedWorldEvent(alice, mock(World.class)));
    this.server.runTasks();
    verify(alice).sendResourcePacks(any(ResourcePackRequest.class));

    final List<Player> expectedRefused = List.of(alice);
    assertEquals(expectedRefused, this.refused);

    // a viewer who left before the next tick is not asked
    final CraftPlayer gone = this.server.addPlayer(UUID.randomUUID());
    viewers.add(gone.getUniqueId());
    join.execute(listener, new PlayerJoinEvent(gone, (Component) null));
    this.server.runTasks();
    verify(gone, never()).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void shouldMeasureHowLongAClientTookFromTheOfferToThePackLoaded() throws EventException {
    final CraftPlayer alice = this.online(ALICE);
    final CraftPlayer bob = this.online(BOB);
    this.packs.start();
    final EventExecutor status = this.registered(PlayerResourcePackStatusEvent.class);
    final EventExecutor quit = this.registered(PlayerQuitEvent.class);
    final Listener listener = this.listener(PlayerJoinEvent.class);
    final PlayerResourcePackStatusEvent early = new PlayerResourcePackStatusEvent(alice, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED);
    final long earlyLoadTime = this.packs.onResourcePackStatus(early);
    assertEquals(-1, earlyLoadTime, "no pack is served yet");

    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE, BOB)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();

    this.milliseconds.addAndGet(2_500);

    final long otherPackLoadTime = this.packs.onResourcePackStatus(
      new PlayerResourcePackStatusEvent(alice, UUID.randomUUID(), Status.SUCCESSFULLY_LOADED)
    );
    assertEquals(-1, otherPackLoadTime);

    final long downloadedLoadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.DOWNLOADED));
    assertEquals(-1, downloadedLoadTime);

    final long loadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
    assertEquals(2_500, loadTime);

    final long repeatedLoadTime = this.packs.onResourcePackStatus(
      new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED)
    );
    assertEquals(-1, repeatedLoadTime, "measured once");

    // the registered listeners reach the same code, and a player who left is forgotten
    status.execute(listener, new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
    quit.execute(listener, new PlayerQuitEvent(bob, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    final long departedLoadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(bob, pack, Status.SUCCESSFULLY_LOADED));
    assertEquals(-1, departedLoadTime);
  }

  @ParameterizedTest
  @ValueSource(strings = { "fabric", "neoforge" })
  void shouldWarnModdedViewersAndAdminsWithoutTreatingTheBrandAsADecoderFailure(final String brand) {
    final CraftPlayer alice = this.online(ALICE);
    when(alice.getName()).thenReturn(ALICE_NAME);
    when(alice.getClientBrandName()).thenReturn(brand);
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();
    final long downloadedLoadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.DOWNLOADED));
    assertEquals(-1, downloadedLoadTime);
    verify(alice, never()).sendMessage(any(Component.class));

    this.load(alice, pack);
    try (final LogCapture logs = LogCapture.capture(Mcv2PackServer.class)) {
      final long loadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
      assertEquals(0, loadTime);

      final long repeatedLoadTime = this.packs.onResourcePackStatus(
        new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED)
      );
      assertEquals(-1, repeatedLoadTime);
      verify(alice).sendMessage(
        Component.text(
          "MCV2: your modded client may replace the video shaders. If video is blank or frozen, install the MCAV MCV2 Client mod, which shows you the dithered maps while Iris shaders are on, or turn off Iris shaders. Without the mod, MCAV cannot detect active shader settings.",
          NamedTextColor.YELLOW
        )
      );

      final String expectedWarning =
        "MCV2 pack loaded by Alice (client brand %s) without the MCAV MCV2 Client mod, so active shaders cannot be verified. Iris shader packs can prevent decoding: with the mod, such a player sees the dithered maps.".formatted(
          brand
        );
      final List<String> expectedWarnings = List.of(expectedWarning);
      final List<String> warnings = logs
        .getEvents()
        .stream()
        .filter(event -> event.getLevel().equals(Level.WARN))
        .map(LogCapture.RecordedEvent::getMessage)
        .toList();
      assertEquals(expectedWarnings, warnings);
    }

    final boolean loaded = this.packs.getViewers().isLoaded(ALICE);
    assertTrue(loaded, "a brand advisory must preserve working Sodium clients");
  }

  @Test
  void shouldNotWarnAModdedViewerWhoseMcv2ClientModReportedTheirShaders() {
    final CraftPlayer alice = this.online(ALICE);
    when(alice.getName()).thenReturn(ALICE_NAME);
    when(alice.getClientBrandName()).thenReturn("fabric");
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();
    this.server.receivePluginMessage(alice, "mcav:mcv2", new byte[] { 1, 1, 0, 0 });
    this.load(alice, pack);
    try (final LogCapture logs = LogCapture.capture(Mcv2PackServer.class)) {
      final long loadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
      assertEquals(0, loadTime);
      verify(alice, never()).sendMessage(any(Component.class));

      final boolean noWarnings = logs
        .getEvents()
        .stream()
        .noneMatch(event -> event.getLevel().equals(Level.WARN));
      assertTrue(noWarnings);
    }

    final boolean loaded = this.packs.getViewers().isLoaded(ALICE);
    assertTrue(loaded, "shaders off, as the mod reported");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "vanilla")
  void shouldNotInferAModdedRendererFromAnUnknownOrVanillaBrand(final @Nullable String brand) {
    final CraftPlayer alice = this.online(ALICE);
    when(alice.getClientBrandName()).thenReturn(brand);
    this.packs.start();
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final UUID pack = this.packs.getViewers().getPackId();
    try (final LogCapture logs = LogCapture.capture(Mcv2PackServer.class)) {
      final long loadTime = this.packs.onResourcePackStatus(new PlayerResourcePackStatusEvent(alice, pack, Status.SUCCESSFULLY_LOADED));
      assertEquals(0, loadTime);
      verify(alice, never()).sendMessage(any(Component.class));

      final boolean noWarnings = logs
        .getEvents()
        .stream()
        .noneMatch(event -> event.getLevel().equals(Level.WARN));
      assertTrue(noWarnings);
    }
  }

  @Test
  void shouldStopThePreviousHttpHostingThatHoldsThePortOfANewOne() {
    final List<HttpHosting> servers = new ArrayList<>();
    this.packs = this.packServer(archive -> this.recordHttpHosting(archive, servers));
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of()));
    this.settle();

    final InOrder order = inOrder(servers.getFirst(), servers.get(1));
    order.verify(servers.getFirst()).start();
    order.verify(servers.getFirst()).shutdown();
    order.verify(servers.get(1)).start();
  }

  private PackHosting recordHttpHosting(final Path archive, final List<HttpHosting> servers) {
    final HttpHosting hosting = mock(HttpHosting.class);
    when(hosting.getZip()).thenReturn(archive);
    when(hosting.getRawUrl()).thenReturn("http://203.0.113.7:8080");
    servers.add(hosting);

    return hosting;
  }

  @Test
  void shouldNotServeAPackThatCannotBeHostedAndTryAgainOnTheNextChange() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs = this.packServer(this::rejectFirstHosting);
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));

    final boolean firstPackExists = Files.exists(this.folder.resolve(FIRST_PACK_FILE_NAME));
    assertFalse(firstPackExists);

    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();

    this.requestSentTo(alice);
  }

  private PackHosting rejectFirstHosting(final Path archive) {
    if (this.hostings.isEmpty()) {
      this.hostings.add(mock(PackHosting.class));
      final String message = "upload failed!";
      throw new IllegalStateException(message);
    }

    return this.hosting(archive);
  }

  @Test
  void shouldClearThePacksAnEarlierRunLeftAndNothingElse() throws IOException {
    Files.writeString(this.folder.resolve(OLD_PACK_FILE_NAME), "old");
    Files.writeString(this.folder.resolve(NOTES_FILE_NAME), "kept");

    this.packs.start();
    this.writer.drain();

    final boolean oldPackExists = Files.exists(this.folder.resolve(OLD_PACK_FILE_NAME));
    assertFalse(oldPackExists);

    final boolean notesExist = Files.exists(this.folder.resolve(NOTES_FILE_NAME));
    assertTrue(notesExist);
  }

  @Test
  void shouldReportAFolderThatCannotBeCreatedAndNotServeThePack() throws IOException {
    final Path file = this.folder.resolve(BLOCKING_FILE_NAME);
    Files.writeString(file, "not a folder");
    final QueuedExecutor own = new QueuedExecutor();
    final Mcv2PackServer blocked = new Mcv2PackServer(
      file,
      this::hosting,
      Mcv2Pack.DebugView.HIDDEN,
      this.offered::add,
      this.refused::add,
      own,
      this.milliseconds::get
    );
    blocked.start();
    blocked.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle(own, this.server);
    final boolean nothingHosted = this.hostings.isEmpty();
    assertTrue(nothingHosted);

    blocked.shutdown();
  }

  @Test
  void shouldStopHostingCloseTheLeasesAndServeNothingMoreOnShutdown() {
    final CraftPlayer alice = this.online(ALICE);
    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      this.packs.start();
      this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
      this.settle();
      final Mcv2PackServer.Lease pending = this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of(ALICE)));
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
      assertThrows(IllegalStateException.class, () -> this.packs.open(this.screen(SCREEN_WIDTH, Set.of())));
    }
  }

  @Test
  void shouldStopAtOnceAPackHostedWhileTheServerShutsDown() {
    this.packs = this.packServer(this::hostPackDuringShutdown);
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));

    this.settle();

    verify(this.hostings.getFirst()).start();
    verify(this.hostings.getFirst()).shutdown();

    final int scheduledTaskCount = this.server.getScheduledTaskCount();
    assertEquals(0, scheduledTaskCount);
  }

  private PackHosting hostPackDuringShutdown(final Path archive) {
    final PackHosting hosting = this.hosting(archive);
    this.packs.shutdown();

    return hosting;
  }

  @Test
  void shouldWriteOrServeNothingAfterShutdown() {
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.server.runTasks();
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of()));

    this.packs.shutdown();
    this.server.runTasks();

    final boolean nothingHosted = this.hostings.isEmpty();
    assertTrue(nothingHosted);

    final int scheduledTaskCount = this.server.getScheduledTaskCount();
    assertEquals(0, scheduledTaskCount);
  }

  @Test
  void shouldRetireAPackHostedTooLateWhenTheShutdownCouldNotWaitForTheWriter() {
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.server.runTasks();
    this.writer.drain();
    // the pack is hosted; the shutdown is interrupted before the writer stops it, then the main thread serves it
    this.writer.interrupt = true;
    this.packs.shutdown();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);

    this.server.runTasks();
    verify(this.hostings.getFirst()).shutdown();

    final boolean firstPackExists = Files.exists(this.folder.resolve(FIRST_PACK_FILE_NAME));
    assertFalse(firstPackExists);
  }

  @Test
  void shouldKeepTheInterruptWhenShutdownIsInterrupted() {
    this.writer.interrupt = true;
    this.packs.shutdown();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
  }

  @Test
  void shouldUnregisterNothingWhenShutDownWithoutStart() {
    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      this.packs.shutdown();
      lists.verifyNoInteractions();
    }
  }

  @Test
  void shouldUseAThreadOfItsOwnAsTheDefaultWriter() throws InterruptedException {
    final AtomicReference<Thread> writerThread = new AtomicReference<>();
    final Mcv2PackServer own = new Mcv2PackServer(
      this.folder,
      archive -> this.recordHostingThread(archive, writerThread),
      Mcv2Pack.DebugView.SHOWN,
      this.offered::add,
      this.refused::add
    );
    try {
      own.start();
      own.open(this.screen(SCREEN_WIDTH, Set.of()));
      this.server.runTasks();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (this.hostings.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
    } finally {
      own.shutdown();
    }

    final Thread callingThread = Thread.currentThread();
    final Thread packThread = writerThread.get();
    assertNotEquals(callingThread, packThread, "pack creation must leave the calling thread");

    final int hostingCount = this.hostings.size();
    assertEquals(1, hostingCount);
    verify(this.hostings.getFirst()).shutdown();
  }

  private PackHosting recordHostingThread(final Path archive, final AtomicReference<Thread> writerThread) {
    writerThread.set(Thread.currentThread());

    return this.hosting(archive);
  }

  @Test
  void shouldRejectMissingArguments() {
    assertThrows(NullPointerException.class, () ->
      new Mcv2PackServer(null, this::hosting, Mcv2Pack.DebugView.HIDDEN, this.offered::add, this.refused::add)
    );
    assertThrows(NullPointerException.class, () ->
      new Mcv2PackServer(this.folder, null, Mcv2Pack.DebugView.HIDDEN, this.offered::add, this.refused::add)
    );
    assertThrows(NullPointerException.class, () ->
      new Mcv2PackServer(this.folder, this::hosting, null, this.offered::add, this.refused::add)
    );
    assertThrows(NullPointerException.class, () ->
      new Mcv2PackServer(this.folder, this::hosting, Mcv2Pack.DebugView.HIDDEN, null, this.refused::add)
    );
    assertThrows(NullPointerException.class, () ->
      new Mcv2PackServer(this.folder, this::hosting, Mcv2Pack.DebugView.HIDDEN, this.offered::add, null)
    );
  }

  @Test
  void shouldHashAFileAndReportWhatItCannotHash() throws IOException {
    final Path file = this.folder.resolve(PACK_FILE_NAME);
    Files.writeString(file, "abc");
    final String digest = Mcv2PackServer.hash(file, HASH_ALGORITHM);
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest);

    assertThrows(Mcv2Exception.class, () -> Mcv2PackServer.hash(this.folder.resolve(MISSING_PACK_FILE_NAME), HASH_ALGORITHM));
    assertThrows(AssertionError.class, () -> Mcv2PackServer.hash(file, "NO-SUCH-DIGEST"));
  }

  @Test
  void shouldLeaveAPackThatCannotBeDeletedForTheNextStart() throws IOException {
    this.packs = this.packServer(this::hostUndeletableFolder);
    Files.writeString(this.folder.resolve(KEPT_FILE_NAME), "x");
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    this.packs.open(this.screen(SMALL_SCREEN_WIDTH, Set.of()));
    this.settle();
    verify(this.hostings.getFirst()).shutdown();

    final boolean folderKept = Files.isDirectory(this.folder);
    assertTrue(folderKept);
  }

  private PackHosting hostUndeletableFolder(final Path archive) {
    final PackHosting hosting = this.hosting(archive);
    // a folder with a file in it cannot be deleted
    when(hosting.getZip()).thenReturn(this.folder);

    return hosting;
  }

  @Test
  void shouldDescribeTheSlotsOfAPack() {
    final Mcv2Configuration first = this.screen(SCREEN_WIDTH, Set.of()).withSlot(1, Mcv2Configuration.DEFAULT_PAGE_MAP, 0);
    final Mcv2Configuration second = this.screen(SMALL_SCREEN_WIDTH, Set.of()).withSlot(2, Mcv2Configuration.DEFAULT_PAGE_MAP, 0);
    final String description = Mcv2PackServer.describe(List.of(first, second));
    assertEquals("slots 1: 320x96, 2: 160x96", description);
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

  @Test
  void shouldShareOneSlotOfThePackBetweenScreensThatPlayOneAfterAnother() throws IOException {
    this.packs.start();
    for (int width = SMALL_SCREEN_WIDTH; width <= 352; width += 32) {
      this.packs.open(this.screen(width, Set.of())).close();
      this.settle();
    }

    final Mcv2PackServer.Lease last = this.packs.open(this.screen(384, Set.of()));
    this.settle();
    final long streamId = last.getConfiguration().getStreamId();
    assertEquals(1, streamId);

    final List<String> expectedSlots = List.of("1: 384x96");
    final List<String> slots = this.slotsOfTheLastPack();
    assertEquals(expectedSlots, slots, "one screen at a time needs one slot of the pack");
  }

  @Test
  void shouldKeepOnlyTheSlotOfTheSizeLeftLastWhenAScreenStepsThroughThreeSizes() throws IOException {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    final Mcv2Configuration playing = lease.getConfiguration();
    lease.resize(playing.withVideo(STEPPED_SCREEN_WIDTH, SCREEN_HEIGHT));
    lease.resize(playing.withVideo(ALTERNATE_SCREEN_WIDTH, SCREEN_HEIGHT));
    this.settle();
    final List<String> expectedSlots = List.of("1: 256x96", "2: 288x96");
    final List<String> slots = this.slotsOfTheLastPack();
    assertEquals(expectedSlots, slots, "the first size gave its slot to the third");

    final int hosted = this.hostings.size();

    final long streamId = lease.resize(playing.withVideo(STEPPED_SCREEN_WIDTH, SCREEN_HEIGHT)).getConfiguration().getStreamId();
    assertEquals(2, streamId);

    this.settle();

    final int hostingCount = this.hostings.size();
    assertEquals(hosted, hostingCount, "back at the size it left last, the pack stays");

    lease.close();
  }

  @Test
  void shouldRemoveTheSlotsOfScreensThatStoppedFromThePackAfterAMinute() throws IOException {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease video = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    final Mcv2PackServer.Lease machine = this.packs.open(this.screen(STEPPED_SCREEN_WIDTH, Set.of(ALICE)));
    final Mcv2PackServer.Lease desktop = this.packs.open(this.screen(ALTERNATE_SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final UUID threeSlots = this.packOf(this.requestSentTo(alice));
    machine.close();
    desktop.close();

    this.milliseconds.addAndGet(A_MINUTE - 1);
    this.server.runLaterTasks();
    this.settle();
    final int hostingCount = this.hostings.size();
    assertEquals(1, hostingCount, "within the minute a screen of either size takes its slot without a reload");

    this.milliseconds.addAndGet(1);
    this.server.runLaterTasks();
    this.settle();
    final List<String> expectedSlots = List.of(FIRST_SLOT_DESCRIPTION);
    final List<String> slots = this.slotsOfTheLastPack();
    assertEquals(expectedSlots, slots);
    verify(alice).removeResourcePacks(threeSlots);

    final int tasksRun = this.server.runLaterTasks();
    assertEquals(0, tasksRun, "nothing is left to take out");

    video.close();
  }

  @Test
  void shouldRemoveThePackFromThePlayersAMinuteAfterTheLastScreenStopped() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final UUID pack = this.packOf(this.requestSentTo(alice));
    this.load(alice, pack);
    lease.close();

    this.milliseconds.addAndGet(A_MINUTE);
    this.server.runLaterTasks();
    this.settle();

    verify(alice).removeResourcePacks(pack);
    verify(this.hostings.getFirst()).shutdown();

    final int hostingCount = this.hostings.size();
    assertEquals(1, hostingCount, "no screen plays, so no pack is written");

    final boolean loaded = this.packs.getViewers().isLoaded(ALICE);
    assertFalse(loaded);

    // the next screen brings a pack again
    this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    this.settle();
    final int hostingCountAfterNextScreen = this.hostings.size();
    assertEquals(2, hostingCountAfterNextScreen);
    verify(alice, Mockito.times(2)).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void shouldGiveANewScreenOfTheSizeAPlayingScreenLeftThatScreensSpare() {
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    final Mcv2Configuration requested = first.getConfiguration();
    first.resize(requested.withVideo(SMALL_SCREEN_WIDTH, SMALL_SCREEN_HEIGHT));
    this.settle();
    final int hosted = this.hostings.size();
    // the first screen plays at 160 in slot 2 and keeps slot 1, at 320, as its spare; no slot of 320 is free
    final Mcv2PackServer.Lease second = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    final long secondStreamId = second.getConfiguration().getStreamId();
    assertEquals(1, secondStreamId, "the pack has the size, as the playing screen's spare");

    final int hostingCount = this.hostings.size();
    assertEquals(hosted, hostingCount, "so the pack stays");
    // the spare is the second screen's now, so stepping back takes a slot of its own
    final long steppedBackStreamId = first.resize(requested.withVideo(SCREEN_WIDTH, SCREEN_HEIGHT)).getConfiguration().getStreamId();
    assertEquals(3, steppedBackStreamId);

    second.close();
    first.close();
  }

  @Test
  void shouldReshapeTheSpareLeftFirstInAFullPackWhereverItsSlotIs() throws IOException {
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int screen = 0; screen < Mcv2Pack.MAXIMUM_SCREENS / 2; screen++) {
      final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH + 32 * screen, Set.of()));
      lease.resize(lease.getConfiguration().withVideo(SMALL_SCREEN_WIDTH + 16 * screen, SMALL_SCREEN_HEIGHT));
      leases.add(lease);
    }

    // the first screen steps back, so its spare is now slot 2, left last, while the spares of slots 3, 5 and 7 are older
    final Mcv2PackServer.Lease firstScreen = leases.getFirst();
    final long steppedBackStreamId = firstScreen
      .resize(firstScreen.getConfiguration().withVideo(SCREEN_WIDTH, SCREEN_HEIGHT))
      .getConfiguration()
      .getStreamId();
    assertEquals(1, steppedBackStreamId);

    this.settle();

    final Mcv2PackServer.Lease fifth = this.packs.open(this.screen(SPARE_SCREEN_WIDTH, Set.of()));

    final long fifthStreamId = fifth.getConfiguration().getStreamId();
    assertEquals(3, fifthStreamId, "the spare left first, behind a newer one");

    this.settle();
    final boolean hasTheNewSize = this.slotsOfTheLastPack().contains("3: 608x96");
    assertTrue(hasTheNewSize, "the slot has the new screen's size");
  }

  @Test
  void shouldTrimTheSlotOfAClosedScreenOnTheTickItsGraceEnds() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    lease.close();
    // the 60 seconds of grace are 1,200 ticks of 50 ms
    verify(this.server.getScheduler()).runTaskLater(any(Plugin.class), any(Runnable.class), eq(1_200L));
  }

  @Test
  void shouldAskAgainForWhatIsLeftOfTheLastGraceWhenTrimmingBeforeItEnds() {
    this.packs.start();
    final Mcv2PackServer.Lease first = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    final Mcv2PackServer.Lease second = this.packs.open(this.screen(STEPPED_SCREEN_WIDTH, Set.of()));
    this.settle();
    first.close();
    this.milliseconds.addAndGet(20_000);
    second.close();
    this.milliseconds.addAndGet(40_000);
    // the trim takes the first slot out, and the second has 20 seconds of its grace left: 400 ticks
    this.server.runLaterTasks();
    verify(this.server.getScheduler()).runTaskLater(any(Plugin.class), any(Runnable.class), eq(400L));
  }

  @Test
  void shouldKeepASpareThroughTheTrimOfTheSlotsAroundIt() throws IOException {
    this.packs.start();
    final Mcv2PackServer.Lease playing = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    final Mcv2Configuration requested = playing.getConfiguration();
    playing.resize(requested.withVideo(SMALL_SCREEN_WIDTH, SMALL_SCREEN_HEIGHT));
    final Mcv2PackServer.Lease stopped = this.packs.open(this.screen(STEPPED_SCREEN_WIDTH, Set.of()));
    this.settle();
    stopped.close();

    this.milliseconds.addAndGet(A_MINUTE);
    this.server.runLaterTasks();
    this.settle();

    final List<String> expectedSlots = List.of(FIRST_SLOT_DESCRIPTION, "2: 160x90");
    final List<String> sortedSlots = this.slotsOfTheLastPack().stream().sorted().toList();
    assertEquals(expectedSlots, sortedSlots);

    final long steppedBackStreamId = playing.resize(requested.withVideo(SCREEN_WIDTH, SCREEN_HEIGHT)).getConfiguration().getStreamId();
    assertEquals(1, steppedBackStreamId, "the spare is kept");

    playing.close();
  }

  @Test
  void shouldScheduleNoTrimWhileThePluginIsBeingDisabled() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    when(this.server.getPlugin().isEnabled()).thenReturn(false);
    lease.close();
    final int tasksRun = this.server.runLaterTasks();
    assertEquals(0, tasksRun, "its pack server is shut down next, which takes the slots out");
  }

  @Test
  void shouldChangeNothingWhenATrimIsDueAfterTheShutdown() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    lease.close();
    this.packs.shutdown();
    final int hosted = this.hostings.size();

    this.milliseconds.addAndGet(A_MINUTE);
    final int tasksRun = this.server.runLaterTasks();
    assertEquals(1, tasksRun, "the trim asked for before the shutdown");

    this.settle();

    final int hostingCount = this.hostings.size();
    assertEquals(hosted, hostingCount);
  }

  @Test
  void shouldLeaveNoPackToWithdrawWhenAScreenStopsBeforeItsPackWasWritten() {
    final CraftPlayer alice = this.online(ALICE);
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of(ALICE)));
    // the tick asks for the pack, which the writer has not written yet when the screen stops and its slot leaves
    this.server.runTasks();
    lease.close();

    this.milliseconds.addAndGet(A_MINUTE);
    this.server.runLaterTasks();
    this.settle();

    assertEquals(List.of(), this.hostings, "the pack asked for was out of date before it was written");
    verify(alice, never()).sendResourcePacks(any(ResourcePackRequest.class));
    verify(alice, never()).removeResourcePacks(any(UUID.class));
  }

  @Test
  void shouldChangeNothingOnAResizeToTheSizeThatPlays() {
    this.packs.start();
    final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH, Set.of()));
    this.settle();
    final Mcv2Configuration requested = lease.getConfiguration();
    final int hosted = this.hostings.size();

    final Mcv2Channel same = lease.resize(requested.withVideo(SCREEN_WIDTH, SCREEN_HEIGHT));
    this.settle();

    final long expectedStreamId = requested.getStreamId();
    final long streamId = same.getConfiguration().getStreamId();
    assertEquals(expectedStreamId, streamId);

    final int hostingCount = this.hostings.size();
    assertEquals(hosted, hostingCount);

    lease.close();
  }

  @Test
  void shouldGiveANewScreenOfAnotherSizeTheSpareOfAPlayingScreenOnlyWhenThePackIsFull() {
    this.packs.start();
    final List<Mcv2PackServer.Lease> leases = new ArrayList<>();
    for (int screen = 0; screen < Mcv2Pack.MAXIMUM_SCREENS / 2; screen++) {
      final Mcv2PackServer.Lease lease = this.packs.open(this.screen(SCREEN_WIDTH + 32 * screen, Set.of()));
      lease.resize(lease.getConfiguration().withVideo(SMALL_SCREEN_WIDTH + 16 * screen, SMALL_SCREEN_HEIGHT));
      leases.add(lease);
    }

    this.settle();

    // four screens, each in the slot of its size and with the spare of the size it left: every slot is taken
    final Mcv2PackServer.Lease fifth = this.packs.open(this.screen(SPARE_SCREEN_WIDTH, Set.of()));

    final long fifthStreamId = fifth.getConfiguration().getStreamId();
    assertEquals(1, fifthStreamId, "the spare left first");

    // the first screen's spare is gone: stepping back takes the spare left next, the second screen's
    final Mcv2Configuration first = leases.getFirst().getConfiguration();
    final long steppedBackStreamId = leases
      .getFirst()
      .resize(first.withVideo(SCREEN_WIDTH, SCREEN_HEIGHT))
      .getConfiguration()
      .getStreamId();
    assertEquals(3, steppedBackStreamId);
  }

  private static final class QueuedExecutor extends AbstractExecutorService {

    private final Deque<Runnable> queue = new ArrayDeque<>();

    private boolean isShutdown;

    private boolean interrupt;

    @Override
    public void execute(final Runnable command) {
      if (this.isShutdown) {
        throw new RejectedExecutionException("shut down!");
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
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
      if (this.interrupt) {
        throw new InterruptedException("interrupted!");
      }

      this.drain();

      return true;
    }

    @Override
    public boolean isShutdown() {
      return this.isShutdown;
    }

    @Override
    public boolean isTerminated() {
      return this.isShutdown && this.queue.isEmpty();
    }
  }
}
