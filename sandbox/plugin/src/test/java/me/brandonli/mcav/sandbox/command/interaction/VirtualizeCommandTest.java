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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import me.brandonli.mcav.sandbox.utils.DiskImages;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @TempDir
  private Path dataFolder;

  private Path imageFolder;

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getServer()).thenReturn(server);
    when(this.plugin.isQemuInstalled()).thenReturn(true);
    when(this.plugin.getDataPath()).thenReturn(this.dataFolder);
    this.imageFolder = DiskImages.folderOf(this.dataFolder);
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

  @Test
  void releasesTheMachineAndScreenWhenSubmissionIsRejected() {
    final java.util.concurrent.RejectedExecutionException failure = new java.util.concurrent.RejectedExecutionException("executor stopped");
    when(this.machine.startAsync(any(VMSettings.class), any(VMPlayer.Architecture.class), any(VMConfiguration.class), any())).thenThrow(
      failure
    );
    final java.util.concurrent.RejectedExecutionException thrown = assertThrows(java.util.concurrent.RejectedExecutionException.class, () ->
      this.create("640x480", "5x4", "")
    );
    assertSame(failure, thrown);
    verify(this.machine).release();
    final List<CompressedMapResult> created = this.maps.constructed();
    final CompressedMapResult screen = created.getFirst();
    verify(screen).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
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

  private void assertArguments(final String commandLine, final String... expected) {
    final VMConfiguration configuration = VirtualizeCommand.parseOptions(commandLine, this.imageFolder);
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of(expected);
    assertEquals(expectedArguments, arguments);
  }

  /**
   * Creates a disk image of the image folder and returns the path the machine is given for it.
   */
  private String image(final String name) throws IOException {
    final Path image = this.imageFolder.resolve(name);
    Files.createDirectories(this.imageFolder);
    Files.writeString(image, "disk image");
    return image.toAbsolutePath().normalize().toString();
  }

  private IllegalArgumentException assertRefusedOptions(final String commandLine) {
    return assertThrows(IllegalArgumentException.class, () -> VirtualizeCommand.parseOptions(commandLine, this.imageFolder));
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
  void bootsTheMachineOnTheScreen() throws IOException {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    this.startsWith(start);
    final String image = this.image("alpine linux.iso");

    this.create("640x480", "5x4", "-cdrom \"alpine linux.iso\" -m 2048M");

    this.assertDithersOntoTheScreen();
    final ArgumentCaptor<VMConfiguration> configurations = ArgumentCaptor.forClass(VMConfiguration.class);
    final VMSettings settings = VMSettings.of(640, 480, 30);
    verify(this.machine).startAsync(eq(settings), eq(VMPlayer.Architecture.X86_64), configurations.capture(), any());
    final VMConfiguration configuration = configurations.getValue();
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of("-cdrom", image, "-m", "2048M");
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
  void needsTheInteractPermissionForInput() {
    final String permission = this.command.getInteractionPermission();
    assertEquals("mcav.vm.interact", permission, "clicks and chat are the input of the interact subcommand");
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
  void refusesScreensLargerThanTheLimit() {
    this.create("640x480", "65x4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void refusesResolutionsLargerThanTheLimit() {
    this.create("100000x100000", "5x4", "");

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
  void parsesOptionsWithQuotedValues() throws IOException {
    final String image = this.image("alpine linux.iso");
    this.assertArguments("-cdrom \"alpine linux.iso\" -m 2048M -enable-kvm", "-cdrom", image, "-m", "2048M", "-enable-kvm");
  }

  @Test
  void keepsOptionsThatQemuAcceptsMoreThanOnce() throws IOException {
    final String first = this.image("a.img");
    final String second = this.image("b.img");
    this.assertArguments(
        "-drive file=a.img,media=disk -drive file=b.img",
        "-drive",
        "file=" + first + ",media=disk",
        "-drive",
        "file=" + second
      );
  }

  @Test
  void keepsTheLastValueOfOptionsThatQemuAcceptsOnce() {
    this.assertArguments("-m 1G -m 2G", "-m", "2G");
  }

  @Test
  void treatsOptionsWithoutValueAsFlags() {
    this.assertArguments("-no-reboot -snapshot", "-no-reboot", "-snapshot");
  }

  @Test
  void treatsAnOptionFollowedByAnotherOptionAsTwoFlags() {
    final VMConfiguration configuration = VirtualizeCommand.parseOptions("-no-reboot -snapshot", this.imageFolder);
    final boolean noReboot = configuration.has("no-reboot");
    final boolean snapshot = configuration.has("snapshot");
    assertTrue(noReboot);
    assertTrue(snapshot, "the second option is a flag of its own, not the value of the first");
  }

  @Test
  void skipsWordsThatAreNotOptions() {
    this.assertArguments("stray -m 512M words", "-m", "512M");
  }

  @Test
  void keepsAnEmptyQuotedValue() {
    this.assertArguments("-name \"\" -snapshot", "-name", "", "-snapshot");
  }

  @Test
  void parsesNothingFromAnEmptyCommandLine() {
    this.assertArguments("   ");
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
  void refusesOptionsThatQemuWouldUseToReachTheServer() throws IOException {
    this.image("alpine.iso");
    final List<String> refused = List.of(
      "-monitor tcp:0.0.0.0:4444,server,nowait",
      "-plugin libanything.so",
      "-virtfs local,path=/,mount_tag=host,security_model=none",
      "-chardev file,id=c,path=anywhere",
      "-netdev user,id=n,hostfwd=tcp::2222-:22",
      "-vnc 0.0.0.0:1",
      "-device usb-host",
      "-bios firmware.bin",
      "-daemonize",
      "-nographic"
    );
    for (final String options : refused) {
      final IllegalArgumentException failure = this.assertRefusedOptions(options);
      final String message = failure.getMessage();
      final boolean explained = message.startsWith("Unsupported QEMU option");
      assertTrue(explained, options + " -> " + message);
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "-cdrom ../outside.iso",
      "-cdrom /etc/passwd",
      "-drive file=../outside.iso",
      "-drive file=/etc/passwd,media=disk",
      "-hda ../../elsewhere.img",
    }
  )
  void refusesDiskImagesOutsideTheImageFolder(final String options) {
    final IllegalArgumentException failure = this.assertRefusedOptions(options);
    final String message = failure.getMessage();
    final boolean explained = message.contains("lies outside the iso folder") || message.contains("no disk image");
    assertTrue(explained, message);
  }

  @Test
  void refusesADiskImageThatIsNotThere() throws IOException {
    this.image("present.iso");
    final IllegalArgumentException failure = this.assertRefusedOptions("-cdrom absent.iso");
    final String message = failure.getMessage();
    assertEquals("There is no disk image absent.iso in the iso folder of the plugin", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "-machine dumpdtb=/tmp/tree.dtb", "-name C:\\windows\\name", "-drive file=a.img,logappend=/tmp/log" })
  void refusesHardwareOptionsThatNameAFile(final String options) throws IOException {
    this.image("a.img");
    final IllegalArgumentException failure = this.assertRefusedOptions(options);
    final String message = failure.getMessage();
    final boolean explained = message.contains("must not name a file");
    assertTrue(explained, message);
  }

  @Test
  void refusesADriveWithoutADiskImage() {
    final IllegalArgumentException failure = this.assertRefusedOptions("-drive if=none,id=empty");
    final String message = failure.getMessage();
    final boolean explained = message.startsWith("A drive names its disk image exactly once");
    assertTrue(explained, message);
  }

  @Test
  void refusesASwitchThatIsGivenAValue() {
    final IllegalArgumentException failure = this.assertRefusedOptions("-snapshot yes");
    final String message = failure.getMessage();
    assertEquals("The QEMU option -snapshot takes no value", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "-m", "-cdrom", "-drive" })
  void refusesAnOptionThatMissesItsValue(final String options) {
    final IllegalArgumentException failure = this.assertRefusedOptions(options);
    final String message = failure.getMessage();
    final String expected = "The QEMU option " + options + " needs a value";
    assertEquals(expected, message);
  }

  @Test
  void tellsTheSenderWhenAnOptionIsRefusedAndStartsNothing() {
    this.create("640x480", "5x4", "-plugin libanything.so");

    final List<Component> messages = Components.received(this.sender);
    final boolean toldOnce = messages.size() == 1;
    assertTrue(toldOnce, messages.toString());
    this.machines.verifyNoInteractions();
    assertNull(this.command.player);
    assertNull(this.command.result);
  }

  @Test
  void listsTheSupportedOptionsWithADashAndInOrder() {
    final List<String> supported = VirtualizeCommand.supportedOptions();
    final List<String> sorted = new ArrayList<>(supported);
    Collections.sort(sorted);
    assertEquals(sorted, supported);
    assertTrue(supported.contains("-cdrom"), "booting a disk image is the reason the options exist");
    assertTrue(supported.contains("-m"));
    assertFalse(supported.contains("-plugin"), "no option may load a library of the server");
    assertFalse(supported.contains("-virtfs"), "no option may share a folder of the server with the guest");
    assertFalse(supported.contains("-monitor"), "no option may publish the monitor of QEMU");
    assertFalse(supported.contains("-vnc"), "the display stays on the loopback address the player chose for it");
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
