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
package me.brandonli.mcav.bukkit.media.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapRegion;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

final class CompressedMapResultPropertyTest {

  private static final String SEED = "20260925";
  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final int START_MAP_ID = 3;
  private static final int MAP_SIZE = MapLayout.MAP_SIZE;
  private static final int MAXIMUM_QUIET_FRAMES = 7;
  private static final short NEVER_SENT = -1;

  private static final int PER_MILLE = 1000;

  private FakeServer server;

  @BeforeTry
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(FIRST);
    this.server.injectModule();
  }

  @AfterTry
  void stopServer() {
    this.server.close();
  }

  // jqwik 1.10 shares a range between derived arbitraries, so each range starts from a fresh one
  private Arbitrary<Integer> between(final int minimum, final int maximum) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(minimum, maximum);
  }

  @Provide
  Arbitrary<ResizeScenario> scenarios() {
    final Arbitrary<Integer> columns = this.between(1, 3);
    final Arbitrary<Integer> rows = this.between(1, 3);
    final Arbitrary<Integer> smallBudgets = this.between(1, 20_000);
    final Arbitrary<Integer> largeBudgets = this.between(20_000, 1 << 20);
    final Arbitrary<Integer> budgets = Arbitraries.oneOf(smallBudgets, largeBudgets);
    final Arbitrary<Step> step = this.steps();
    final ListArbitrary<Step> stepList = step.list();
    final ListArbitrary<Step> someSteps = stepList.ofMinSize(1);
    final ListArbitrary<Step> boundedSteps = someSteps.ofMaxSize(8);
    final Combinators.Combinator4<Integer, Integer, Integer, List<Step>> scenarios = Combinators.combine(
      columns,
      rows,
      budgets,
      boundedSteps
    );
    return scenarios.as(ResizeScenario::new);
  }

  private Arbitrary<Step> steps() {
    final Arbitrary<Integer> shares = this.between(1, PER_MILLE);
    final Arbitrary<Integer> colors = this.between(1, 60);
    final Arbitrary<Integer> kinds = Arbitraries.of(Step.FRAME, Step.FRAME, Step.FRAME, Step.JOIN, Step.LEAVE);
    final Combinators.Combinator4<Integer, Integer, Integer, Integer> combined = Combinators.combine(kinds, shares, shares, colors);
    return combined.as(Step::new);
  }

  @Property(seed = SEED, tries = 40)
  void shouldNotLetAnyViewerKeepThePictureOfAnEarlierFrame(@ForAll("scenarios") final ResizeScenario scenario) {
    final List<UUID> viewers = new CopyOnWriteArrayList<>(List.of(FIRST, SECOND));
    final MapConfiguration configuration = scenario.createConfiguration(viewers);
    final CompressedMapResult result = new CompressedMapResult(configuration, scenario.getBudget());
    final FrameSource frames = new FrameSource();
    final ViewerModels models = new ViewerModels(scenario);

    final Step lastFrame = scenario
      .getSteps()
      .stream()
      .filter(step -> step.getKind() != Step.JOIN && step.getKind() != Step.LEAVE)
      .reduce((earlier, later) -> later)
      .orElse(null);
    for (final Step step : scenario.getSteps()) {
      switch (step.getKind()) {
        case Step.JOIN -> this.connectSecondViewer(models);
        case Step.LEAVE -> this.disconnectSecondViewer(models);
        default -> frames.process(result, scenario, step);
      }

      models.receive(this.server);
    }

    if (lastFrame == null) {
      return;
    }

    int quietFrames = 0;
    int extraFrames = 0;
    final int frameLimit = MAXIMUM_QUIET_FRAMES * (scenario.getMapCount() * 65) + MAXIMUM_QUIET_FRAMES;
    while (quietFrames < MAXIMUM_QUIET_FRAMES) {
      final boolean withinLimit = extraFrames < frameLimit;
      assertTrue(withinLimit, "the viewers did not catch up");
      frames.process(result, scenario, lastFrame);
      final boolean sent = models.receive(this.server);
      quietFrames = sent ? 0 : quietFrames + 1;
      extraFrames++;
    }

    final byte[] picture = scenario.createPicture(lastFrame);
    final MapLayout layout = scenario.createLayout(lastFrame);
    models.assertShow(layout, picture);

    result.release();
    models.receive(this.server);
    models.assertTransparent();
  }

  private void connectSecondViewer(final ViewerModels models) {
    final boolean online = PacketUtils.isConnected(SECOND);
    if (online) {
      return;
    }

    this.server.addPlayer(SECOND);
    PacketUtils.initialize();
    models.connect(SECOND);
  }

  private void disconnectSecondViewer(final ViewerModels models) {
    final boolean online = PacketUtils.isConnected(SECOND);
    if (!online) {
      return;
    }

    this.server.removePlayer(SECOND);
    PacketUtils.initialize();
    models.disconnect(SECOND);
  }

  private static final class FrameSource {

    private final DitherAlgorithm algorithm;
    private byte[] nextFrame;

    FrameSource() {
      this.algorithm = mock(DitherAlgorithm.class);
      this.nextFrame = new byte[0];
      when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(_ -> this.nextFrame.clone());
    }

    private void process(final CompressedMapResult result, final ResizeScenario scenario, final Step step) {
      final int width = scenario.widthOf(step);
      final int height = scenario.heightOf(step);
      final ImageBuffer samples = mock(ImageBuffer.class);
      when(samples.getWidth()).thenReturn(width);
      when(samples.getHeight()).thenReturn(height);
      this.nextFrame = scenario.createPicture(step);
      result.process(samples, this.algorithm);
    }
  }

  private static final class Step {

    private static final int FRAME = 0;
    private static final int JOIN = 1;
    private static final int LEAVE = 2;

    private final int kind;
    private final int widthShare;
    private final int heightShare;
    private final int color;

    Step(final int kind, final int widthShare, final int heightShare, final int color) {
      this.kind = kind;
      this.widthShare = widthShare;
      this.heightShare = heightShare;
      this.color = color;
    }

    private int getKind() {
      return this.kind;
    }

    @Override
    public String toString() {
      return switch (this.kind) {
        case JOIN -> "join";
        case LEAVE -> "leave";
        default -> "frame %sx%s color %s".formatted(this.widthShare, this.heightShare, this.color);
      };
    }
  }

  private static final class ResizeScenario {

    private final int columns;
    private final int rows;
    private final int budget;
    private final List<Step> steps;

    ResizeScenario(final int columns, final int rows, final int budget, final List<Step> steps) {
      this.columns = columns;
      this.rows = rows;
      this.budget = budget;
      this.steps = steps;
    }

    private MapConfiguration createConfiguration(final List<UUID> viewers) {
      final MapConfigurationBuilder builder = MapConfiguration.builder();
      builder.viewers(viewers);
      builder.map(START_MAP_ID);
      builder.mapBlockWidth(this.columns);
      builder.mapBlockHeight(this.rows);
      builder.resize(false);
      return builder.build();
    }

    private int widthOf(final Step step) {
      final int maximum = this.columns * MAP_SIZE + 60;
      return Math.max(1, (step.widthShare * maximum) / PER_MILLE);
    }

    private int heightOf(final Step step) {
      final int maximum = this.rows * MAP_SIZE + 60;
      return Math.max(1, (step.heightShare * maximum) / PER_MILLE);
    }

    private MapLayout createLayout(final Step step) {
      final int width = this.widthOf(step);
      final int height = this.heightOf(step);
      return MapLayout.of(START_MAP_ID, this.columns, this.rows, width, height);
    }

    // never 0, the transparent index, so a cleared pixel stands out
    private byte[] createPicture(final Step step) {
      final int width = this.widthOf(step);
      final int height = this.heightOf(step);
      final byte[] picture = new byte[width * height];
      for (int index = 0; index < picture.length; index++) {
        final int row = index / width;
        final int stripe = (row / 8) % 2;
        picture[index] = (byte) (step.color + stripe * 64);
      }

      return picture;
    }

    private int getBudget() {
      return this.budget;
    }

    private List<Step> getSteps() {
      return this.steps;
    }

    private int getMapCount() {
      return this.columns * this.rows;
    }

    @Override
    public String toString() {
      return "maps %sx%s, budget %s, steps %s".formatted(this.columns, this.rows, this.budget, this.steps);
    }
  }

  private static final class ViewerModels {

    private final ResizeScenario scenario;
    private final List<Viewer> viewers;

    ViewerModels(final ResizeScenario scenario) {
      this.scenario = scenario;
      this.viewers = new ArrayList<>();
      this.connect(FIRST);
    }

    private void connect(final UUID uuid) {
      final Viewer viewer = new Viewer(uuid, this.scenario.getMapCount());
      this.viewers.add(viewer);
    }

    private void disconnect(final UUID uuid) {
      this.viewers.removeIf(viewer -> viewer.uuid.equals(uuid));
    }

    private boolean receive(final FakeServer server) {
      boolean received = false;
      for (final Viewer viewer : this.viewers) {
        final List<Packet<?>> packets = server.getSentPackets(viewer.uuid);
        final int count = packets.size();
        for (int index = viewer.applied; index < count; index++) {
          final Packet<?> packet = packets.get(index);
          viewer.apply(packet);
          received = true;
        }
        viewer.applied = count;
      }

      return received;
    }

    private void assertShow(final MapLayout layout, final byte[] picture) {
      for (final Viewer viewer : this.viewers) {
        for (int index = 0; index < viewer.maps.length; index++) {
          this.assertMapShows(layout, picture, viewer, index);
        }
      }
    }

    private void assertMapShows(final MapLayout layout, final byte[] picture, final Viewer viewer, final int index) {
      final int imageWidth = layout.getImageWidth();
      final MapRegion region = layout.getRegion(index);
      final short[] map = viewer.maps[index];
      for (int pixel = 0; pixel < MAP_SIZE * MAP_SIZE; pixel++) {
        final int row = pixel / MAP_SIZE;
        final int column = pixel % MAP_SIZE;
        final short shown = map[pixel];
        final int regionX = column - region.getLocalX();
        final int regionY = row - region.getLocalY();
        final boolean covered = regionX >= 0 && regionY >= 0 && regionX < region.getWidth() && regionY < region.getHeight();
        final String where = "viewer %s, map %s at %s,%s".formatted(viewer.uuid, index, column, row);
        if (covered) {
          final short expected = picture[(region.getSourceY() + regionY) * imageWidth + region.getSourceX() + regionX];
          assertEquals(expected, shown, () -> "%s shows the current frame".formatted(where));

          continue;
        }

        final boolean clean = shown == 0 || shown == NEVER_SENT;
        assertTrue(clean, () -> "%s keeps color %s of an earlier frame".formatted(where, shown));
      }
    }

    private void assertTransparent() {
      for (final Viewer viewer : this.viewers) {
        for (int index = 0; index < viewer.maps.length; index++) {
          this.assertMapTransparent(viewer, index);
        }
      }
    }

    private void assertMapTransparent(final Viewer viewer, final int index) {
      final short[] map = viewer.maps[index];
      for (int pixel = 0; pixel < map.length; pixel++) {
        final short shown = map[pixel];
        final String where = "viewer %s, map %s pixel %s".formatted(viewer.uuid, index, pixel);
        assertEquals(0, shown, () -> "%s is transparent after the release".formatted(where));
      }
    }
  }

  private static final class Viewer {

    private final UUID uuid;
    private final short[][] maps;
    private int applied;

    Viewer(final UUID uuid, final int mapCount) {
      this.uuid = uuid;
      this.maps = new short[mapCount][MAP_SIZE * MAP_SIZE];
      for (final short[] map : this.maps) {
        Arrays.fill(map, NEVER_SENT);
      }
    }

    private void apply(final Packet<?> packet) {
      final List<ClientboundMapItemDataPacket> mapPackets = MapPackets.unbundle(packet);
      for (final ClientboundMapItemDataPacket mapPacket : mapPackets) {
        final MapId id = mapPacket.mapId();
        final int index = id.id() - START_MAP_ID;
        final boolean inGrid = index >= 0 && index < this.maps.length;
        assertTrue(inGrid, () -> "a packet for map %s, outside of the grid".formatted(id.id()));

        final Optional<MapItemSavedData.MapPatch> colorPatch = mapPacket.colorPatch();
        final MapItemSavedData.MapPatch patch = colorPatch.orElseThrow();
        this.paint(this.maps[index], patch);
      }
    }

    private void paint(final short[] map, final MapItemSavedData.MapPatch patch) {
      final byte[] colors = patch.mapColors();
      final int width = patch.width();
      for (int row = 0; row < patch.height(); row++) {
        for (int column = 0; column < width; column++) {
          map[(patch.startY() + row) * MAP_SIZE + patch.startX() + column] = colors[row * width + column];
        }
      }
    }
  }
}
