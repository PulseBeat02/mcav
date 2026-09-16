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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vm.ExecutableNotInPathException;
import me.brandonli.mcav.vm.VMConfiguration;
import me.brandonli.mcav.vm.VMPlayer;
import me.brandonli.mcav.vm.VMSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link VirtualizeCommand}. The virtual machine, the maps and the dithering are mocked.
 */
final class VirtualizeCommandTest {

  private MCAVSandbox plugin;
  private VirtualizeCommand command;
  private CommandSender sender;
  private MultiplePlayerSelector selector;
  private VMPlayer machine;
  private VideoAttachableCallback callback;
  private FunctionalVideoFilter ditherFilter;
  private MockedStatic<VMPlayer> machines;
  private MockedStatic<DitherFilter> dithers;
  private MockedConstruction<CompressedMapResult> maps;

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getServer()).thenReturn(server);
    when(this.plugin.isQemuInstalled()).thenReturn(true);
    this.command = new VirtualizeCommand(this.plugin);
    this.sender = mock(CommandSender.class);
    this.selector = mock(MultiplePlayerSelector.class);
    final Player viewer = mock(Player.class);
    final List<Player> viewers = List.of(viewer);
    when(this.selector.values()).thenReturn(viewers);

    this.machine = mock(VMPlayer.class);
    this.callback = mock(VideoAttachableCallback.class);
    when(this.machine.getVideoAttachableCallback()).thenReturn(this.callback);
    this.machines = Mockito.mockStatic(VMPlayer.class);
    this.machines.when(VMPlayer::create).thenReturn(this.machine);
    this.ditherFilter = mock(FunctionalVideoFilter.class);
    this.dithers = Mockito.mockStatic(DitherFilter.class);
    this.dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(this.ditherFilter);
    this.maps = Mockito.mockConstruction(CompressedMapResult.class);
  }

  @AfterEach
  void closeMocks() {
    this.machines.close();
    this.dithers.close();
    this.maps.close();
    this.command.shutdown();
  }

  private void create(final String resolution, final String blocks, final String flags) {
    this.command.createVM(
        this.sender,
        this.selector,
        resolution,
        30,
        blocks,
        0,
        DitheringArgument.FILTER_LITE,
        VMPlayer.Architecture.X86_64,
        flags
      );
  }

  private void startsWith(final CompletableFuture<Boolean> start) {
    when(this.machine.startAsync(any(VMSettings.class), any(VMPlayer.Architecture.class), any(VMConfiguration.class), any())).thenReturn(
      start
    );
  }

  private void assertReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private static void assertArguments(final String commandLine, final String... expected) {
    final VMConfiguration configuration = VirtualizeCommand.parseOptions(commandLine);
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of(expected);
    assertEquals(expectedArguments, arguments);
  }

  private static void assertTokens(final String commandLine, final String... expected) {
    final List<String> tokens = VirtualizeCommand.tokenize(commandLine);
    final List<String> expectedTokens = List.of(expected);
    assertEquals(expectedTokens, tokens);
  }

  private void assertDithersOntoTheScreen() {
    final ArgumentCaptor<VideoPipelineStep> pipelines = ArgumentCaptor.forClass(VideoPipelineStep.class);
    verify(this.callback).attach(pipelines.capture());
    final VideoPipelineStep pipeline = pipelines.getValue();
    final VideoFilter filter = pipeline.getFilter();
    assertSame(this.ditherFilter, filter);
  }

  @Test
  void bootsTheMachineOnTheScreen() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    this.startsWith(start);

    this.create("640x480", "5x4", "-cdrom \"C:/My Images/alpine.iso\" -m 2048M");

    this.assertDithersOntoTheScreen();
    final ArgumentCaptor<VMConfiguration> configurations = ArgumentCaptor.forClass(VMConfiguration.class);
    final VMSettings settings = VMSettings.of(640, 480, 30);
    verify(this.machine).startAsync(eq(settings), eq(VMPlayer.Architecture.X86_64), configurations.capture(), any());
    final VMConfiguration configuration = configurations.getValue();
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of("-cdrom", "C:/My Images/alpine.iso", "-m", "2048M");
    assertEquals(expectedArguments, arguments);
    assertSame(this.machine, this.command.player);
    final Component loading = Message.VM_LOADING.build();
    final Component created = Message.VM_CREATE.build();
    this.assertReceived(loading, created);
  }

  @Test
  void releasesTheMachineWhenQemuIsMissingFromThePath() {
    final ExecutableNotInPathException missing = new ExecutableNotInPathException("qemu-system-x86_64");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missing);
    this.startsWith(start);

    this.create("640x480", "5x4", "");

    verify(this.machine).release();
    assertNull(this.command.player);
    final Component loading = Message.VM_LOADING.build();
    final Component path = Message.VM_PATH.build();
    this.assertReceived(loading, path);
  }

  @Test
  void refusesWhenQemuIsNotInstalled() {
    when(this.plugin.isQemuInstalled()).thenReturn(false);

    this.create("640x480", "5x4", "");

    final Component error = Message.QEMU_NOT_INSTALLED.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void refusesInvalidResolutions() {
    this.create("640", "5x4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void refusesInvalidScreenSizes() {
    this.create("640x480", "5x-4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void describesTheOutcomeOfTheStart() {
    final ExecutableNotInPathException missing = new ExecutableNotInPathException("qemu-system-arm");
    final CompletionException wrapped = new CompletionException(missing);
    final IllegalStateException crash = new IllegalStateException("crashed");
    final CompletionException other = new CompletionException(crash);

    final Component success = this.command.createStartMessage(true, null);
    final Component direct = this.command.createStartMessage(false, missing);
    final Component unwrapped = this.command.createStartMessage(false, wrapped);
    final Component crashed = this.command.createStartMessage(false, other);
    final Component refused = this.command.createStartMessage(false, null);

    final Component created = Message.VM_CREATE.build();
    final Component path = Message.VM_PATH.build();
    final Component failed = Message.VM_ERROR.build();
    assertEquals(created, success);
    assertEquals(path, direct);
    assertEquals(path, unwrapped);
    assertEquals(failed, crashed);
    assertEquals(failed, refused);
  }

  @Test
  void forwardsClicksAndTextToTheMachine() {
    this.command.handleLeftClick(this.machine, 1, 2);
    this.command.handleRightClick(this.machine, 3, 4);
    this.command.handleTextInput(this.machine, "ls");

    verify(this.machine).sendMouseEvent(MouseClick.LEFT, 1, 2);
    verify(this.machine).sendMouseEvent(MouseClick.RIGHT, 3, 4);
    verify(this.machine).sendKeyEvent("ls");
  }

  @Test
  void releasesTheMachineWhenAsked() {
    this.command.player = this.machine;

    this.command.releaseVM(this.sender);
    this.command.releaseVM(this.sender);

    verify(this.machine).release();
    final Component released = Message.VM_RELEASE.build();
    this.assertReceived(released, released);
  }

  @Test
  void togglesTheChatInteractionOfAPlayer() {
    final Player player = mock(Player.class);

    this.command.toggleInteraction(player);
    this.command.toggleInteraction(player);

    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    final List<Component> messages = Components.received(player);
    final List<Component> expectedMessages = List.of(enabled, disabled);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void parsesOptionsWithQuotedValues() {
    assertArguments(
      "-cdrom \"C:/My Images/alpine.iso\" -m 2048M -enable-kvm",
      "-cdrom",
      "C:/My Images/alpine.iso",
      "-m",
      "2048M",
      "-enable-kvm"
    );
  }

  @Test
  void keepsOptionsThatQemuAcceptsMoreThanOnce() {
    assertArguments(
      "-drive file=a.img -drive file=b.img -device virtio-net",
      "-drive",
      "file=a.img",
      "-drive",
      "file=b.img",
      "-device",
      "virtio-net"
    );
  }

  @Test
  void keepsTheLastValueOfOptionsThatQemuAcceptsOnce() {
    assertArguments("-m 1G -m 2G", "-m", "2G");
  }

  @Test
  void treatsOptionsWithoutValueAsFlags() {
    assertArguments("-nographic -snapshot", "-nographic", "-snapshot");
  }

  @Test
  void treatsAnOptionFollowedByAnotherOptionAsTwoFlags() {
    final VMConfiguration configuration = VirtualizeCommand.parseOptions("-nographic -snapshot");
    final boolean nographic = configuration.has("nographic");
    final boolean snapshot = configuration.has("snapshot");
    assertTrue(nographic);
    assertTrue(snapshot, "the second option is a flag of its own, not the value of the first");
  }

  @Test
  void skipsWordsThatAreNotOptions() {
    assertArguments("stray -m 512M words", "-m", "512M");
  }

  @Test
  void keepsAnEmptyQuotedValue() {
    assertArguments("-name \"\" -snapshot", "-name", "", "-snapshot");
  }

  @Test
  void parsesNothingFromAnEmptyCommandLine() {
    assertArguments("   ");
  }

  @Test
  void splitsAtUnquotedWhitespace() {
    assertTokens("a  \"b c\"\t\"\" x\"y z\"w \"open quote", "a", "b c", "", "xy zw", "open quote");
  }

  @Test
  void tokenizesAnEmptyLineToNothing() {
    assertTokens("");
  }

  @Test
  void reportsAMachineThatFailsToStartWithoutBlamingTheInstallation() {
    final IllegalStateException crash = new IllegalStateException("qemu crashed");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(crash);
    this.startsWith(start);

    this.create("640x480", "5x4", "");

    verify(this.machine).release();
    assertNull(this.command.player);
    final Component loading = Message.VM_LOADING.build();
    final Component failed = Message.VM_ERROR.build();
    this.assertReceived(loading, failed);
  }
}
