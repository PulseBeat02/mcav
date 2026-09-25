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

/**
 * Properties of {@link CompressedMapResult} for sequences of frames whose size changes, while a second viewer comes and
 * goes. Pass 1 listed as an open defect that maps falling out of a shrinking picture kept their stale picture; this
 * states what the viewers must see for every such sequence, checked on the packets they actually receive: once the
 * picture stops changing, every pixel the current frame covers shows that frame, and every other pixel of the grid is
 * transparent or was never sent anything. No pixel keeps the colors of an earlier frame. After a release, every map of
 * the grid is transparent.
 */
final class CompressedMapResultPropertyTest {

  private static final String SEED = "20260925";
  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final int START_MAP_ID = 3;
  private static final int MAP_SIZE = MapLayout.MAP_SIZE;
  private static final int MAX_QUIET_FRAMES = 7;
  private static final short NEVER_SENT = -1;

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

  /**
   * Creates integers in a range from a fresh arbitrary: jqwik 1.9 shares the range between an arbitrary and the ones
   * configured from it, so one base configured twice would hand every user the last range.
   */
  private static Arbitrary<Integer> between(final int min, final int max) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(min, max);
  }

  @Provide
  Arbitrary<ResizeScenario> scenarios() {
    final Arbitrary<Integer> columns = between(1, 3);
    final Arbitrary<Integer> rows = between(1, 3);
    final Arbitrary<Integer> smallBudgets = between(1, 20_000);
    final Arbitrary<Integer> largeBudgets = between(20_000, 1 << 20);
    final Arbitrary<Integer> budgets = Arbitraries.oneOf(smallBudgets, largeBudgets);
    final Arbitrary<Step> step = steps();
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

  /**
   * A frame of some size and color, or the second viewer joining or leaving. Sizes are thousandths of a little more
   * than the grid, so the picture is sometimes larger than the grid and sometimes much smaller.
   */
  private static Arbitrary<Step> steps() {
    final Arbitrary<Integer> shares = between(1, 1000);
    final Arbitrary<Integer> colors = between(1, 60);
    final Arbitrary<Integer> kinds = Arbitraries.of(Step.FRAME, Step.FRAME, Step.FRAME, Step.JOIN, Step.LEAVE);
    final Combinators.Combinator4<Integer, Integer, Integer, Integer> combined = Combinators.combine(kinds, shares, shares, colors);
    return combined.as(Step::new);
  }

  @Property(seed = SEED, tries = 40)
  void noViewerKeepsThePictureOfAnEarlierFrame(@ForAll("scenarios") final ResizeScenario scenario) {
    final List<UUID> viewers = new CopyOnWriteArrayList<>(List.of(FIRST, SECOND));
    final MapConfiguration configuration = scenario.createConfiguration(viewers);
    final CompressedMapResult result = new CompressedMapResult(configuration, scenario.getBudget());
    final FrameSource frames = new FrameSource();
    final ViewerModels models = new ViewerModels(scenario);

    Step lastFrame = null;
    for (final Step step : scenario.getSteps()) {
      switch (step.getKind()) {
        case Step.JOIN -> this.connectSecondViewer(models);
        case Step.LEAVE -> this.disconnectSecondViewer(models);
        default -> {
          frames.process(result, scenario, step);
          lastFrame = step;
        }
      }
      models.receive(this.server);
    }
    if (lastFrame == null) {
      return;
    }

    // the picture stops changing: repeat the last frame until nothing is sent any more
    int quietFrames = 0;
    int extraFrames = 0;
    final int frameLimit = MAX_QUIET_FRAMES * (scenario.getMapCount() * 65) + MAX_QUIET_FRAMES;
    while (quietFrames < MAX_QUIET_FRAMES) {
      assertTrue(extraFrames < frameLimit, "the viewers did not catch up");
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
    PacketUtils.init();
    models.connect(SECOND);
  }

  private void disconnectSecondViewer(final ViewerModels models) {
    final boolean online = PacketUtils.isConnected(SECOND);
    if (!online) {
      return;
    }
    this.server.removePlayer(SECOND);
    PacketUtils.init();
    models.disconnect(SECOND);
  }

  /**
   * Hands frames to the result the way a player does, with the dithering replaced by the palette indices of the scenario.
   */
  private static final class FrameSource {

    private final DitherAlgorithm algorithm;
    private byte[] nextFrame;

    FrameSource() {
      this.algorithm = mock(DitherAlgorithm.class);
      this.nextFrame = new byte[0];
      when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(_ -> this.nextFrame.clone());
    }

    void process(final CompressedMapResult result, final ResizeScenario scenario, final Step step) {
      final int width = scenario.widthOf(step);
      final int height = scenario.heightOf(step);
      final ImageBuffer samples = mock(ImageBuffer.class);
      when(samples.getWidth()).thenReturn(width);
      when(samples.getHeight()).thenReturn(height);
      this.nextFrame = scenario.createPicture(step);
      result.process(samples, this.algorithm);
    }
  }

  /**
   * One step of a scenario.
   */
  static final class Step {

    static final int FRAME = 0;
    static final int JOIN = 1;
    static final int LEAVE = 2;

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

    int getKind() {
      return this.kind;
    }

    @Override
    public String toString() {
      return switch (this.kind) {
        case JOIN -> "join";
        case LEAVE -> "leave";
        default -> "frame " + this.widthShare + "x" + this.heightShare + " color " + this.color;
      };
    }
  }

  /**
   * A grid, a byte budget and the steps played on it.
   */
  static final class ResizeScenario {

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

    MapConfiguration createConfiguration(final List<UUID> viewers) {
      final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
      builder.viewers(viewers);
      builder.map(START_MAP_ID);
      builder.mapBlockWidth(this.columns);
      builder.mapBlockHeight(this.rows);
      builder.resize(false);
      return builder.build();
    }

    int getBudget() {
      return this.budget;
    }

    List<Step> getSteps() {
      return this.steps;
    }

    int getMapCount() {
      return this.columns * this.rows;
    }

    int widthOf(final Step step) {
      final int maximum = this.columns * MAP_SIZE + 60;
      return Math.max(1, (step.widthShare * maximum) / 1000);
    }

    int heightOf(final Step step) {
      final int maximum = this.rows * MAP_SIZE + 60;
      return Math.max(1, (step.heightShare * maximum) / 1000);
    }

    MapLayout createLayout(final Step step) {
      final int width = this.widthOf(step);
      final int height = this.heightOf(step);
      return new MapLayout(START_MAP_ID, this.columns, this.rows, width, height);
    }

    /**
     * Creates the palette indices of a frame: the color of the step, with a stripe every eight rows, never 0, the
     * transparent index, so a pixel that was cleared can always be told from a pixel of a picture.
     */
    byte[] createPicture(final Step step) {
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

    @Override
    public String toString() {
      return "maps " + this.columns + "x" + this.rows + ", budget " + this.budget + ", steps " + this.steps;
    }
  }

  /**
   * What every connected viewer displays, rebuilt from the map data packets the viewer received, in order. A viewer who
   * connects starts with maps that were never sent anything, as a client that joins does.
   */
  private static final class ViewerModels {

    private final ResizeScenario scenario;
    private final List<Viewer> viewers;

    ViewerModels(final ResizeScenario scenario) {
      this.scenario = scenario;
      this.viewers = new ArrayList<>();
      this.connect(FIRST);
    }

    void connect(final UUID uuid) {
      final Viewer viewer = new Viewer(uuid, this.scenario.getMapCount());
      this.viewers.add(viewer);
    }

    void disconnect(final UUID uuid) {
      this.viewers.removeIf(viewer -> viewer.uuid.equals(uuid));
    }

    /**
     * Applies the packets every viewer received since the last call.
     *
     * @return whether any viewer received anything
     */
    boolean receive(final FakeServer server) {
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

    void assertShow(final MapLayout layout, final byte[] picture) {
      final int imageWidth = layout.getImageWidth();
      for (final Viewer viewer : this.viewers) {
        for (int index = 0; index < viewer.maps.length; index++) {
          final MapRegion region = layout.getRegion(index);
          final short[] map = viewer.maps[index];
          for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
              final short shown = map[y * MAP_SIZE + x];
              final int regionX = x - region.getLocalX();
              final int regionY = y - region.getLocalY();
              final boolean covered = regionX >= 0 && regionY >= 0 && regionX < region.getWidth() && regionY < region.getHeight();
              final String where = "viewer " + viewer.uuid + ", map " + index + " at " + x + "," + y;
              if (covered) {
                final short expected = picture[(region.getSourceY() + regionY) * imageWidth + region.getSourceX() + regionX];
                assertEquals(expected, shown, () -> where + " shows the current frame");
              } else {
                final boolean clean = shown == 0 || shown == NEVER_SENT;
                assertTrue(clean, () -> where + " keeps color " + shown + " of an earlier frame");
              }
            }
          }
        }
      }
    }

    void assertTransparent() {
      for (final Viewer viewer : this.viewers) {
        for (int index = 0; index < viewer.maps.length; index++) {
          final short[] map = viewer.maps[index];
          for (int pixel = 0; pixel < map.length; pixel++) {
            final short shown = map[pixel];
            final String where = "viewer " + viewer.uuid + ", map " + index + " pixel " + pixel;
            assertEquals(0, shown, () -> where + " is transparent after the release");
          }
        }
      }
    }
  }

  /**
   * The maps of one viewer.
   */
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

    void apply(final Packet<?> packet) {
      final List<ClientboundMapItemDataPacket> mapPackets = MapPackets.unbundle(packet);
      for (final ClientboundMapItemDataPacket mapPacket : mapPackets) {
        final MapId id = mapPacket.mapId();
        final int index = id.id() - START_MAP_ID;
        final boolean inGrid = index >= 0 && index < this.maps.length;
        assertTrue(inGrid, () -> "a packet for map " + id.id() + ", outside of the grid");
        final Optional<MapItemSavedData.MapPatch> colorPatch = mapPacket.colorPatch();
        final MapItemSavedData.MapPatch patch = colorPatch.orElseThrow();
        final short[] map = this.maps[index];
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
}
