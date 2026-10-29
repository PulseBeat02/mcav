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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.video.Mcv2Support;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.DiskImages;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vm.ExecutableNotInPathException;
import me.brandonli.mcav.vm.VmConfiguration;
import me.brandonli.mcav.vm.VmPlayer;
import me.brandonli.mcav.vm.VmSettings;
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

  /** How long a test waits for a player stopped in the background. */
  private static final long RELEASE_MILLIS = 5000;

  private MCAVSandbox plugin;
  private VirtualizeCommand command;
  private CommandSender sender;
  private MultiplePlayerSelector selector;
  private Player viewer;
  private VmPlayer machine;
  private AudioProvider provider;
  private VideoAttachableCallback callback;
  private FunctionalVideoFilter ditherFilter;
  private MockedStatic<VmPlayer> machines;
  private MockedStatic<DitherFilter> dithers;
  private MockedConstruction<CompressedMapResult> maps;

  @TempDir
  private Path dataFolder;

  private Path imageFolder;

  private PluginDataConfigurationMapper configuration;

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    final PluginDataConfigurationMapper defaults = mock(PluginDataConfigurationMapper.class);
    when(defaults.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    when(this.plugin.getConfiguration()).thenReturn(defaults);
    this.configuration = defaults;
    when(this.plugin.getServer()).thenReturn(server);
    when(this.plugin.isQemuInstalled()).thenReturn(true);
    when(this.plugin.getDataPath()).thenReturn(this.dataFolder);
    this.provider = mock(AudioProvider.class);
    when(this.plugin.getAudioProvider()).thenReturn(this.provider);
    this.imageFolder = DiskImages.folderOf(this.dataFolder);
    this.command = new VirtualizeCommand(this.plugin);
    this.sender = mock(CommandSender.class);
    this.selector = mock(MultiplePlayerSelector.class);
    this.viewer = mock(Player.class);
    final List<Player> viewers = List.of(this.viewer);
    when(this.selector.values()).thenReturn(viewers);

    this.machine = mock(VmPlayer.class);
    this.callback = mock(VideoAttachableCallback.class);
    when(this.machine.getVideoAttachableCallback()).thenReturn(this.callback);
    when(this.machine.getAudioAttachableCallback()).thenReturn(mock(AudioAttachableCallback.class));
    this.machines = Mockito.mockStatic(VmPlayer.class);
    this.machines.when(VmPlayer::create).thenReturn(this.machine);
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
      VmPlayer.Architecture.X86_64,
      AudioArgument.NONE,
      flags
    );
  }

  @Test
  void shouldReleaseTheMachineAndScreenWhenSubmissionIsRejected() {
    final RejectedExecutionException failure = new RejectedExecutionException("executor stopped!");
    when(this.machine.startAsync(any(VmSettings.class), any(VmPlayer.Architecture.class), any(VmConfiguration.class), any())).thenThrow(
      failure
    );
    final RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class, () -> this.create("640x480", "5x4", ""));
    assertSame(failure, thrown);
    verify(this.machine, timeout(RELEASE_MILLIS)).release();

    final int size = this.maps.constructed().size();
    assertEquals(1, size);

    // the screen's output owns its maps: releasing it releases them
    verify(this.ditherFilter).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
  }

  private void startsWith(final CompletableFuture<Boolean> start) {
    when(this.machine.startAsync(any(VmSettings.class), any(VmPlayer.Architecture.class), any(VmConfiguration.class), any())).thenReturn(
      start
    );
  }

  private void assertReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private void assertArguments(final String commandLine, final String... expected) {
    final VmConfiguration configuration = VirtualizeCommand.parseOptions(commandLine, this.imageFolder).build();
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
  void shouldBootTheMachineOnTheScreen() throws IOException {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    this.startsWith(start);
    final String image = this.image("alpine linux.iso");

    this.create("640x480", "5x4", "-cdrom \"alpine linux.iso\" -m 2048M");

    this.assertDithersOntoTheScreen();
    final ArgumentCaptor<VmConfiguration> configurations = ArgumentCaptor.forClass(VmConfiguration.class);
    final VmSettings settings = VmSettings.of(640, 480, 30);
    verify(this.machine).startAsync(eq(settings), eq(VmPlayer.Architecture.X86_64), configurations.capture(), any());
    final VmConfiguration configuration = configurations.getValue();
    final List<String> arguments = configuration.getArguments();
    final List<String> expectedArguments = List.of("-cdrom", image, "-m", "2048M");
    assertEquals(expectedArguments, arguments);
    assertSame(this.machine, this.command.player);
    final Component loading = Message.VM_LOADING.build();
    final Component created = Message.VM_CREATE.build();
    this.assertReceived(loading, created);
  }

  @Test
  void shouldGiveTheGuestQemusOwnNetworkOnlyWhenTheConfigurationAllowsIt() throws IOException {
    this.startsWith(CompletableFuture.completedFuture(true));
    final String image = this.image("alpine linux.iso");
    when(this.configuration.isVmAllowNetwork()).thenReturn(true);

    this.create("640x480", "5x4", "-cdrom \"alpine linux.iso\"");

    final ArgumentCaptor<VmConfiguration> configurations = ArgumentCaptor.forClass(VmConfiguration.class);
    verify(this.machine).startAsync(any(VmSettings.class), eq(VmPlayer.Architecture.X86_64), configurations.capture(), any());
    final VmConfiguration machine = configurations.getValue();
    final List<String> arguments = machine.getArguments();
    assertEquals(List.of("-cdrom", image, "-nic", "user"), arguments);
  }

  @Test
  void shouldTakeTheCodecFromTheEndOfTheOptions() {
    this.startsWith(CompletableFuture.completedFuture(true));
    final Mcv2Support support = mock(Mcv2Support.class);
    when(this.plugin.getMcv2Support()).thenReturn(support);

    this.create("640x480", "5x4", "-m 2048M --codec MCV2");

    // the screen asked for MCV2, which found no wall here and dithers
    verify(support).configure(eq(this.sender), eq(Pair.pair(5, 4)), eq(Pair.pair(640, 480)), eq(0), eq(Settings.DEFAULT), any());
    final ArgumentCaptor<VmConfiguration> configurations = ArgumentCaptor.forClass(VmConfiguration.class);
    verify(this.machine).startAsync(any(VmSettings.class), eq(VmPlayer.Architecture.X86_64), configurations.capture(), any());

    final List<String> arguments = configurations.getValue().getArguments();
    assertEquals(List.of("-m", "2048M"), arguments);
  }

  @Test
  void shouldRefuseACodecThatIsNeitherDitherNorMcv2() {
    this.create("640x480", "5x4", "-m 2048M --codec vp9");
    this.assertReceived(Message.UNKNOWN_CODEC.build());
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldSplitATrailingCodecOffTheOptions() {
    final VirtualizeCommand.CodecSplit expected = new VirtualizeCommand.CodecSplit("-m 2048M", MapCodec.MCV2);
    final VirtualizeCommand.CodecSplit split = VirtualizeCommand.splitCodec("-m 2048M --codec mcv2");
    assertEquals(expected, split);

    final VirtualizeCommand.CodecSplit secondExpected = new VirtualizeCommand.CodecSplit("-m 2048M", MapCodec.DITHER);
    final VirtualizeCommand.CodecSplit virtualizeCommandSplit = VirtualizeCommand.splitCodec("-m 2048M  --codec\tDiThEr  ");
    assertEquals(secondExpected, virtualizeCommandSplit);

    final VirtualizeCommand.CodecSplit thirdExpected = new VirtualizeCommand.CodecSplit("", MapCodec.MCV2);
    final VirtualizeCommand.CodecSplit secondSplit = VirtualizeCommand.splitCodec("--codec mcv2");
    assertEquals(thirdExpected, secondSplit);

    final VirtualizeCommand.CodecSplit fourthExpected = new VirtualizeCommand.CodecSplit("-m 2048M", null);
    final VirtualizeCommand.CodecSplit thirdSplit = VirtualizeCommand.splitCodec("-m 2048M");
    assertEquals(fourthExpected, thirdSplit);
    // a codec anywhere but at the end is an option for QEMU, which refuses it

    final VirtualizeCommand.CodecSplit fifthExpected = new VirtualizeCommand.CodecSplit("--codec mcv2 -m 2048M", null);
    final VirtualizeCommand.CodecSplit fourthSplit = VirtualizeCommand.splitCodec("--codec mcv2 -m 2048M");
    assertEquals(fifthExpected, fourthSplit);

    final VirtualizeCommand.CodecSplit sixthExpected = new VirtualizeCommand.CodecSplit("-name x--codec mcv2", null);
    final VirtualizeCommand.CodecSplit fifthSplit = VirtualizeCommand.splitCodec("-name x--codec mcv2");
    assertEquals(sixthExpected, fifthSplit);

    final VirtualizeCommand.CodecSplit sixthSplit = VirtualizeCommand.splitCodec("-m 2048M --codec vp9");
    assertNull(sixthSplit);
  }

  @Test
  void shouldReleaseTheMachineWhenQemuIsMissingFromThePath() {
    final ExecutableNotInPathException missing = new ExecutableNotInPathException("qemu-system-x86_64");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missing);
    this.startsWith(start);

    this.create("640x480", "5x4", "");

    verify(this.machine, timeout(RELEASE_MILLIS)).release();
    assertNull(this.command.player);
    final Component loading = Message.VM_LOADING.build();
    final Component path = Message.VM_PATH.build();
    this.assertReceived(loading, path);
  }

  @Test
  void shouldRefuseWhenQemuIsNotInstalled() {
    when(this.plugin.isQemuInstalled()).thenReturn(false);

    this.create("640x480", "5x4", "");

    final Component error = Message.QEMU_NOT_INSTALLED.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldNeedTheInteractPermissionForInput() {
    final String permission = this.command.getInteractionPermission();
    assertEquals("mcav.vm.interact", permission, "clicks and chat are the input of the interact subcommand");
  }

  @Test
  void shouldRefuseInvalidResolutions() {
    this.create("640", "5x4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldRefuseInvalidScreenSizes() {
    this.create("640x480", "5x-4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldRefuseScreensLargerThanTheLimit() {
    this.create("640x480", "65x4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldRefuseResolutionsLargerThanTheLimit() {
    this.create("100000x100000", "5x4", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldDescribeTheOutcomeOfTheStart() {
    final ExecutableNotInPathException missing = new ExecutableNotInPathException("qemu-system-arm");
    final CompletionException wrapped = new CompletionException(missing);
    final IllegalStateException crash = new IllegalStateException("crashed!");
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
  void shouldForwardClicksAndTextToTheMachine() {
    this.command.handleLeftClick(this.machine, 1, 2);
    this.command.handleRightClick(this.machine, 3, 4);
    this.command.handleTextInput(this.machine, "ls");

    verify(this.machine).sendMouseEvent(MouseClick.LEFT, 1, 2);
    verify(this.machine).sendMouseEvent(MouseClick.RIGHT, 3, 4);
    verify(this.machine).sendKeyEvent("ls");
  }

  @Test
  void shouldLetGoOfTheSoundWhenTheMachineFailsToEndWhileThePluginDisables() {
    when(this.provider.constructFilter(eq(AudioArgument.SIMPLE_VOICE_CHAT), any(), any(), eq(this.machine))).thenReturn(
      mock(AudioFilter.class)
    );
    final AudioAttachableCallback sound = mock(AudioAttachableCallback.class);
    when(this.machine.getAudioAttachableCallback()).thenReturn(sound);
    this.startsWith(CompletableFuture.completedFuture(true));
    this.command.createVM(
      this.sender,
      this.selector,
      "640x480",
      30,
      "5x4",
      0,
      DitheringArgument.FILTER_LITE,
      VmPlayer.Architecture.X86_64,
      AudioArgument.SIMPLE_VOICE_CHAT,
      ""
    );
    when(this.plugin.getAudioProvider()).thenThrow(new IllegalStateException("The audio provider is not available!"));
    Mockito.doThrow(new IllegalStateException("release broke!")).when(this.machine).release();
    this.command.releaseVM(this.sender);
    verify(this.provider).releaseAudioFilter(this.machine);
    verify(this.callback).detach();
    verify(sound).detach();
    verify(this.machine, timeout(RELEASE_MILLIS)).release();
  }

  @Test
  void shouldReleaseTheMachineWhenAsked() {
    this.command.player = this.machine;

    this.command.releaseVM(this.sender);
    this.command.releaseVM(this.sender);

    verify(this.machine, timeout(RELEASE_MILLIS)).release();
    final Component released = Message.VM_RELEASE.build();
    this.assertReceived(released, released);
  }

  @Test
  void shouldToggleTheChatInteractionOfAPlayer() {
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
  void shouldParseOptionsWithQuotedValues() throws IOException {
    final String image = this.image("alpine linux.iso");
    this.assertArguments("-cdrom \"alpine linux.iso\" -m 2048M -enable-kvm", "-cdrom", image, "-m", "2048M", "-enable-kvm");
  }

  @Test
  void shouldKeepOptionsThatQemuAcceptsMoreThanOnce() throws IOException {
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
  void shouldKeepTheLastValueOfOptionsThatQemuAcceptsOnce() {
    this.assertArguments("-m 1G -m 2G", "-m", "2G");
  }

  @Test
  void shouldTreatOptionsWithoutValueAsFlags() {
    this.assertArguments("-no-reboot -snapshot", "-no-reboot", "-snapshot");
  }

  @Test
  void shouldTreatAnOptionFollowedByAnotherOptionAsTwoFlags() {
    final VmConfiguration configuration = VirtualizeCommand.parseOptions("-no-reboot -snapshot", this.imageFolder).build();
    final boolean noReboot = configuration.has("no-reboot");
    final boolean snapshot = configuration.has("snapshot");
    assertTrue(noReboot);
    assertTrue(snapshot, "the second option is a flag of its own, not the value of the first");
  }

  @Test
  void shouldSkipWordsThatAreNotOptions() {
    this.assertArguments("stray -m 512M words", "-m", "512M");
  }

  @Test
  void shouldKeepAnEmptyQuotedValue() {
    this.assertArguments("-name \"\" -snapshot", "-name", "", "-snapshot");
  }

  @Test
  void shouldParseNothingFromAnEmptyCommandLine() {
    this.assertArguments("   ");
  }

  @Test
  void shouldSplitAtUnquotedWhitespace() {
    assertTokens("a  \"b c\"\t\"\" x\"y z\"w \"open quote", "a", "b c", "", "xy zw", "open quote");
  }

  @Test
  void shouldTokenizAnEmptyLineToNothing() {
    assertTokens("");
  }

  @Test
  void shouldRefuseOptionsThatQemuWouldUseToReachTheServer() throws IOException {
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
      final boolean explained = message.contains(" is not supported, use one of ");
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
  void shouldRefuseDiskImagesOutsideTheImageFolder(final String options) {
    final IllegalArgumentException failure = this.assertRefusedOptions(options);
    final String message = failure.getMessage();
    final boolean explained = message.contains("lies outside the iso folder") || message.contains("no disk image");
    assertTrue(explained, message);
  }

  @Test
  void shouldRefuseADiskImageThatIsNotThere() throws IOException {
    this.image("present.iso");
    final IllegalArgumentException failure = this.assertRefusedOptions("-cdrom absent.iso");
    final String message = failure.getMessage();
    assertEquals("There is no disk image absent.iso in the iso folder of the plugin!", message);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "-machine dumpdtb=/tmp/tree.dtb",
      "-machine virt,dumpdtb=tree.dtb",
      "-machine pc,firmware=server.properties",
      "-machine q35,kernel=bzImage",
      "-machine pc,pcspk-audiodev=snd0",
      "-boot order=c,splash=logo.bmp",
      "-name C:\\windows\\name",
      "-drive file=a.img,logappend=/tmp/log",
      "-drive file=a.img,logappend=C:\\temp\\log",
    }
  )
  void shouldRefuseHardwareOptionsThatNameAFileOrChangeTheSound(final String options) throws IOException {
    this.image("a.img");
    final IllegalArgumentException failure = this.assertRefusedOptions(options);
    final String message = failure.getMessage();
    final boolean explained = message.contains("must not name a file") || message.contains("is not allowed");
    assertTrue(explained, message);
  }

  @Test
  void shouldAcceptTheHardwareOfCommonMachines() {
    this.assertArguments(
      "-machine q35,accel=kvm,usb=on -cpu host,+ssse3,-avx -smp 4,cores=2 -m 2G,slots=2,maxmem=4G -boot order=dc,menu=on",
      "-machine",
      "q35,accel=kvm,usb=on",
      "-cpu",
      "host,+ssse3,-avx",
      "-smp",
      "4,cores=2",
      "-m",
      "2G,slots=2,maxmem=4G",
      "-boot",
      "order=dc,menu=on"
    );
    this.assertArguments(
      "-accel tcg,thread=multi -rtc base=utc,clock=host -k en-us -vga virtio -name \"my vm\",debug-threads=on",
      "-accel",
      "tcg,thread=multi",
      "-rtc",
      "base=utc,clock=host",
      "-k",
      "en-us",
      "-vga",
      "virtio",
      "-name",
      "my vm,debug-threads=on"
    );
  }

  @Test
  void shouldPlayTheSoundOfAMachineIntoTheChosenOutputAndLetGoOfItOnRelease() {
    final AudioFilter output = mock(AudioFilter.class);
    when(this.provider.constructFilter(eq(AudioArgument.SIMPLE_VOICE_CHAT), any(), any(), eq(this.machine))).thenReturn(output);
    final AudioAttachableCallback audio = mock(AudioAttachableCallback.class);
    when(this.machine.getAudioAttachableCallback()).thenReturn(audio);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.machine.startAsync(any(VmSettings.class), any(VmPlayer.Architecture.class), any(VmConfiguration.class), any())).thenReturn(
      start
    );
    this.command.createVM(
      this.sender,
      this.selector,
      "640x480",
      30,
      "5x4",
      0,
      DitheringArgument.FILTER_LITE,
      VmPlayer.Architecture.X86_64,
      AudioArgument.SIMPLE_VOICE_CHAT,
      "-m 256M"
    );
    final ArgumentCaptor<AudioPipelineStep> pipelines = ArgumentCaptor.forClass(AudioPipelineStep.class);
    verify(audio).attach(pipelines.capture());

    final AudioFilter filter = pipelines.getValue().getFilter();
    assertSame(output, filter);

    this.command.releaseVM(this.sender);
    verify(this.provider).releaseAudioFilter(this.machine);
  }

  private void createWithTheWebPage(final CompletableFuture<Boolean> start) {
    when(this.provider.isHttpEnabled()).thenReturn(true);
    when(this.provider.isHttpReady()).thenReturn(true);
    when(this.provider.constructHttpUrl()).thenReturn("http://mc.example.com:3000/");
    when(this.provider.constructFilter(eq(AudioArgument.HTTP_SERVER), any(), any(), eq(this.machine))).thenReturn(mock(AudioFilter.class));
    when(this.machine.getAudioAttachableCallback()).thenReturn(mock(AudioAttachableCallback.class));
    when(this.machine.startAsync(any(VmSettings.class), any(VmPlayer.Architecture.class), any(VmConfiguration.class), any())).thenReturn(
      start
    );
    this.command.createVM(
      this.sender,
      this.selector,
      "640x480",
      30,
      "5x4",
      0,
      DitheringArgument.FILTER_LITE,
      VmPlayer.Architecture.X86_64,
      AudioArgument.HTTP_SERVER,
      "-m 256M"
    );
  }

  @Test
  void shouldSendTheLinkOfAMachineThatStartsWithTheWebPage() {
    this.createWithTheWebPage(CompletableFuture.completedFuture(true));
    verify(this.provider).constructHttpUrl();

    final List<Component> expected = List.of(Message.AUDIO_HTTP.build("http://mc.example.com:3000/"));
    final List<Component> received = Components.received(this.viewer);
    assertEquals(expected, received);

    final List<String> receivedText = Components.receivedText(this.viewer);
    assertEquals(List.of("Click on the URL to listen onto the website! http://mc.example.com:3000/"), receivedText);
  }

  @Test
  void shouldSendNoLinkForAMachineThatFailsToStart() {
    this.createWithTheWebPage(CompletableFuture.failedFuture(new IllegalStateException("QEMU broke!")));
    verify(this.provider, never()).constructHttpUrl();
  }

  @Test
  void shouldSendNoLinksToTheSoundOfAMachineReleasedWhileItStarts() {
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.createWithTheWebPage(start);
    this.command.releaseVM(this.sender);
    start.complete(true);
    verify(this.provider, never()).constructHttpUrl();
  }

  @Test
  void shouldSendNoLinkForAMachineReleasedBeforeTheMainThreadSendsIt() {
    TestServer.resetWithDeferredTasks();
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.createWithTheWebPage(start);
    TestServer.runPendingTasks();
    start.complete(true);
    // the link waits for the main thread, and the machine is released before it gets there
    this.command.releaseVM(this.sender);
    TestServer.runPendingTasks();
    verify(this.provider, never()).constructHttpUrl();
  }

  @Test
  void shouldNotLetAMachineOfAnArchitectureWithoutSoundChooseAnAudioOutput() {
    this.command.createVM(
      this.sender,
      this.selector,
      "640x480",
      30,
      "5x4",
      0,
      DitheringArgument.FILTER_LITE,
      VmPlayer.Architecture.AARCH64,
      AudioArgument.SIMPLE_VOICE_CHAT,
      "-m 256M"
    );
    this.assertReceived(Message.VM_NO_SOUND.build());
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldSendNoLinkToTheSoundOfAMachineThatDoesNotStart() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(false);
    when(this.machine.startAsync(any(VmSettings.class), any(VmPlayer.Architecture.class), any(VmConfiguration.class), any())).thenReturn(
      start
    );
    this.create("640x480", "5x4", "-m 256M");
    verify(this.provider, never()).constructHttpUrl();
    verify(this.provider, never()).constructVoiceChannelUrl();
  }

  @Test
  void shouldPlayASilentMachineIntoNoOutput() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.machine.startAsync(any(VmSettings.class), any(VmPlayer.Architecture.class), any(VmConfiguration.class), any())).thenReturn(
      start
    );
    this.create("640x480", "5x4", "-m 256M");
    this.command.releaseVM(this.sender);
    verify(this.provider, never()).constructFilter(any(), any(), any(), any());
    verify(this.provider, never()).releaseAudioFilter();
  }

  @Test
  void shouldStartNothingForAnAudioOutputThatCannotPlayNow() {
    this.command.createVM(
      this.sender,
      this.selector,
      "640x480",
      30,
      "5x4",
      0,
      DitheringArgument.FILTER_LITE,
      VmPlayer.Architecture.X86_64,
      AudioArgument.DISCORD_BOT,
      "-m 256M"
    );
    this.assertReceived(Message.UNSUPPORTED_AUDIO.build());
    this.machines.verifyNoInteractions();
  }

  @Test
  void shouldRefuseADrivePropertyThatStartsWithAPathSeparator() {
    for (final String part : new String[] { "/tmp", "\\\\server" }) {
      final IllegalArgumentException failure = this.assertRefusedOptions("-drive " + part + ",file=disk.img");

      final String message = failure.getMessage();
      assertEquals("QEMU option -drive must not name a file, but " + part + " does!", message);
    }
  }

  @Test
  void shouldKeepTheAttachmentPropertiesOfADrive() throws IOException {
    final String image = this.image("disk.img");
    this.assertArguments(
      "-drive file=disk.img,format=raw,if=virtio,media=disk,cache=none,readonly=on,id=boot",
      "-drive",
      "file=" + image + ",format=raw,if=virtio,media=disk,cache=none,readonly=on,id=boot"
    );
  }

  @ParameterizedTest
  @ValueSource(strings = { "driver=file", "node-name=disk", "backing=none", "file.locking=off", "throttling.iops-total=100" })
  void shouldRefuseADrivePropertyOutsideTheList(final String part) throws IOException {
    this.image("disk.img");
    final IllegalArgumentException failure = this.assertRefusedOptions("-drive file=disk.img," + part);
    final String message = failure.getMessage();
    assertEquals("Value file=disk.img," + part + " of the QEMU option -drive contains " + part + ", which is not allowed!", message);
  }

  @Test
  void shouldRefuseAMachineWithMoreMemoryThanTheServerLetsOneHave() {
    final long limit = 4L << 30;
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
      VirtualizeCommand.parseOptions("-m 64G", this.imageFolder, limit)
    );

    final String message = failure.getMessage();
    assertEquals("QEMU option -m 64G asks for more than the 4096 MiB a machine may have on this server!", message);
    assertThrows(IllegalArgumentException.class, () -> VirtualizeCommand.parseOptions("-m size=5G,slots=2", this.imageFolder, limit));

    final Optional<String> found = VirtualizeCommand.parseOptions("-m 4G", this.imageFolder, limit).build().find("m");
    assertEquals(Optional.of("4G"), found);

    final List<String> arguments = VirtualizeCommand.parseOptions("", this.imageFolder, limit).build().getArguments();
    assertEquals(List.of(), arguments, "QEMU's default of 128 MiB");
  }

  @Test
  void shouldAllowAMachineHalfOfTheMemoryOfTheServerAndAtLeast512MiB() {
    final long maximumMemoryBytes = VirtualizeCommand.maxMemoryBytes(16L << 30);
    assertEquals(8L << 30, maximumMemoryBytes);

    final long virtualizeCommandMaximumMemoryBytes = VirtualizeCommand.maxMemoryBytes(256L << 20);
    assertEquals(512L << 20, virtualizeCommandMaximumMemoryBytes);

    final long limit = VirtualizeCommand.maxMemoryBytes();
    assertTrue(limit >= 512L << 20, "at least 512 MiB: " + limit);
  }

  @Test
  void shouldRefuseADriveWithoutADiskImage() {
    final IllegalArgumentException failure = this.assertRefusedOptions("-drive if=none,id=empty");
    final String message = failure.getMessage();
    assertEquals("Drive if=none,id=empty must name its disk image exactly once, as file=<image>!", message);
  }

  @Test
  void shouldRefuseASwitchThatIsGivenAValue() {
    final IllegalArgumentException failure = this.assertRefusedOptions("-snapshot yes");
    final String message = failure.getMessage();
    assertEquals("QEMU option -snapshot takes no value!", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "-m", "-cdrom", "-drive" })
  void shouldRefuseAnOptionThatMissesItsValue(final String options) {
    final IllegalArgumentException failure = this.assertRefusedOptions(options);
    final String message = failure.getMessage();
    final String expected = "QEMU option " + options + " needs a value!";
    assertEquals(expected, message);
  }

  @Test
  void shouldTellTheSenderWhenAnOptionIsRefusedAndStartNothing() {
    this.create("640x480", "5x4", "-plugin libanything.so");

    final List<Component> messages = Components.received(this.sender);
    final boolean toldOnce = messages.size() == 1;
    assertTrue(toldOnce, messages.toString());
    final String accepted = String.join(", ", VirtualizeCommand.supportedOptions());

    final String text = Components.plain(messages.getFirst());
    assertEquals("Invalid QEMU flags! QEMU option -plugin is not supported, use one of " + accepted + " instead!", text);

    this.machines.verifyNoInteractions();
    assertNull(this.command.player);
    assertNull(this.command.result);
  }

  @Test
  void shouldListTheSupportedOptionsWithADashAndInOrder() {
    final List<String> supported = VirtualizeCommand.supportedOptions();
    final List<String> sorted = new ArrayList<>(supported);
    Collections.sort(sorted);
    assertEquals(sorted, supported);

    final boolean contained = supported.contains("-cdrom");
    assertTrue(contained, "booting a disk image is the reason the options exist");

    final boolean supportedContained = supported.contains("-m");
    assertTrue(supportedContained);

    final boolean secondContained = supported.contains("-plugin");
    assertFalse(secondContained, "no option may load a library of the server");

    final boolean thirdContained = supported.contains("-virtfs");
    assertFalse(thirdContained, "no option may share a folder of the server with the guest");

    final boolean fourthContained = supported.contains("-monitor");
    assertFalse(fourthContained, "no option may publish the monitor of QEMU");

    final boolean fifthContained = supported.contains("-vnc");
    assertFalse(fifthContained, "the display stays on the loopback address the player chose for it");
  }

  @Test
  void shouldReportAMachineThatFailsToStartWithoutBlamingTheInstallation() {
    final IllegalStateException crash = new IllegalStateException("qemu crashed!");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(crash);
    this.startsWith(start);

    this.create("640x480", "5x4", "");

    verify(this.machine, timeout(RELEASE_MILLIS)).release();
    assertNull(this.command.player);
    final Component loading = Message.VM_LOADING.build();
    final Component failed = Message.VM_ERROR.build();
    this.assertReceived(loading, failed);
  }
}
