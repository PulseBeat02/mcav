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
package me.brandonli.mcav.sandbox.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.brandonli.mcav.http.HttpResult;
import me.brandonli.mcav.http.MediaInfo;
import me.brandonli.mcav.jda.DiscordPlayer;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.testing.StandardErrorCapture;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.svc.SVCFilter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AudioProvider}.
 *
 * <p>Most tests start the outputs on the calling thread, so they are ready when {@code initialize} returns. The
 * tests about starting in the background use an executor that only collects the tasks, which run when the test
 * says so.
 */
final class AudioProviderTest {

  private final URLParseDump dump = new URLParseDump();
  private final Object[] players = { "Steve" };
  private final List<Runnable> deferred = new ArrayList<>();

  private MCAVSandbox sandbox;
  private PluginDataConfigurationMapper configuration;
  private ExecutorService startup;
  private AudioProvider provider;
  private MockedStatic<JDABuilder> builders;
  private MockedStatic<DiscordPlayer> discordPlayers;
  private MockedStatic<HttpResult> httpResults;
  private MockedStatic<SVCFilter> svcFilters;

  private JDABuilder builder;
  private JDA jda;
  private Guild guild;
  private VoiceChannel channel;
  private AudioManager audioManager;
  private DiscordPlayer discord;
  private HttpResult httpServer;
  private SVCFilter voiceChatFilter;

  @BeforeEach
  void createProvider() {
    TestServer.reset();
    this.sandbox = mock(MCAVSandbox.class);
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(this.sandbox.getConfiguration()).thenReturn(this.configuration);
    when(this.configuration.getDiscordBotToken()).thenReturn("token");
    when(this.configuration.getDiscordBotGuildId()).thenReturn("100");
    when(this.configuration.getDiscordBotChannelId()).thenReturn("200");
    when(this.configuration.getHttpHostName()).thenReturn("mc.example.com");
    when(this.configuration.getHttpPort()).thenReturn(3000);
    this.startup = MoreExecutors.newDirectExecutorService();
    this.provider = new AudioProvider(this.sandbox, this.startup);
    this.mockOutputs();
  }

  private void mockOutputs() {
    this.builder = mock(JDABuilder.class);
    this.jda = mock(JDA.class);
    this.guild = mock(Guild.class);
    this.channel = mock(VoiceChannel.class);
    this.audioManager = mock(AudioManager.class);
    this.discord = mock(DiscordPlayer.class);
    this.httpServer = mock(HttpResult.class);
    this.voiceChatFilter = mock(SVCFilter.class);
    when(this.builder.build()).thenReturn(this.jda);
    when(this.jda.getGuildById("100")).thenReturn(this.guild);
    when(this.guild.getVoiceChannelById("200")).thenReturn(this.channel);
    when(this.guild.getAudioManager()).thenReturn(this.audioManager);
    this.builders = Mockito.mockStatic(JDABuilder.class);
    // JDA requires the result of createLight to be used, so the stubbed call assigns it to an unnamed variable
    this.builders
      .when(() -> {
        final JDABuilder _ = JDABuilder.createLight("token", GatewayIntent.GUILD_VOICE_STATES);
      })
      .thenReturn(this.builder);
    this.discordPlayers = Mockito.mockStatic(DiscordPlayer.class);
    this.discordPlayers.when(() -> DiscordPlayer.voice(this.jda)).thenReturn(this.discord);
    this.httpResults = Mockito.mockStatic(HttpResult.class);
    this.httpResults.when(() -> HttpResult.http("mc.example.com", 3000)).thenReturn(this.httpServer);
    this.svcFilters = Mockito.mockStatic(SVCFilter.class);
    this.svcFilters.when(() -> SVCFilter.svc(this.players)).thenReturn(this.voiceChatFilter);
  }

  @AfterEach
  void closeStaticMocks() {
    this.builders.close();
    this.discordPlayers.close();
    this.httpResults.close();
    this.svcFilters.close();
    this.startup.shutdownNow();
  }

  private void enableDiscord() {
    when(this.configuration.isDiscordBotEnabled()).thenReturn(true);
  }

  private void enableHttp() {
    when(this.configuration.isHttpEnabled()).thenReturn(true);
  }

  // an executor that keeps the tasks until runDeferred(), like a thread that has not got to them yet
  private void startInTheBackground() {
    final ExecutorService executor = mock(ExecutorService.class);
    doAnswer(invocation -> {
      final Runnable task = invocation.getArgument(0);
      this.deferred.add(task);
      return null;
    })
      .when(executor)
      .execute(any(Runnable.class));
    this.startup = executor;
    this.provider = new AudioProvider(this.sandbox, executor);
  }

  private int runDeferred() {
    final List<Runnable> tasks = new ArrayList<>(this.deferred);
    this.deferred.clear();
    for (final Runnable task : tasks) {
      task.run();
    }
    return tasks.size();
  }

  private String discordFailure() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players)
    );
    return exception.getMessage();
  }

  private String httpFailure() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players)
    );
    return exception.getMessage();
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> new AudioProvider(null));
    assertThrows(NullPointerException.class, () -> new AudioProvider(this.sandbox, null));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(null, this.dump, this.players));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, null, this.players));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, this.dump, null));
  }

  @Test
  void linksToTheConfiguredVoiceChannel() {
    final String url = this.provider.constructVoiceChannelUrl();
    assertEquals("https://discord.com/channels/100/200", url);
  }

  @Test
  void linksToTheConfiguredWebPageBeforeItStarts() {
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
  }

  @Test
  void reportsWhichOutputsAreEnabled() {
    final boolean discordEnabledBefore = this.provider.isDiscordBotEnabled();
    final boolean httpEnabledBefore = this.provider.isHttpEnabled();
    assertFalse(discordEnabledBefore);
    assertFalse(httpEnabledBefore);
    this.enableDiscord();
    this.enableHttp();
    final boolean discordEnabledAfter = this.provider.isDiscordBotEnabled();
    final boolean httpEnabledAfter = this.provider.isHttpEnabled();
    assertTrue(discordEnabledAfter);
    assertTrue(httpEnabledAfter);
  }

  @Test
  void startsNothingWhenEveryOutputIsDisabled() {
    this.provider.initialize();
    this.builders.verifyNoInteractions();
    this.httpResults.verifyNoInteractions();
    final PluginManager pluginManager = TestServer.pluginManager();
    verifyNoInteractions(pluginManager);
    final boolean discordReady = this.provider.isDiscordBotReady();
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(discordReady);
    assertFalse(httpReady);
  }

  @Test
  void logsTheBotInAndPlaysIntoTheConfiguredChannel() throws InterruptedException {
    this.enableDiscord();
    this.provider.initialize();
    verify(this.builder).enableCache(CacheFlag.VOICE_STATE);
    verify(this.jda).awaitReady();
    final boolean ready = this.provider.isDiscordBotReady();
    assertTrue(ready);
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    assertPlaysInto(this.discord, filter);
    final InOrder order = inOrder(this.discord, this.audioManager);
    order.verify(this.discord).flush();
    order.verify(this.audioManager).setSendingHandler(this.discord);
    order.verify(this.audioManager).openAudioConnection(this.channel);
    order.verify(this.discord).setCurrentMedia(this.dump);
  }

  @Test
  void logsTheBotInWithoutMakingTheCallerWait() throws InterruptedException {
    this.startInTheBackground();
    this.enableDiscord();
    this.provider.initialize();
    verify(this.builder).build();
    verify(this.jda, never()).awaitReady();
    final boolean readyWhileStarting = this.provider.isDiscordBotReady();
    assertFalse(readyWhileStarting);
    final String message = this.discordFailure();
    assertEquals("The Discord bot is not ready", message);
    final int ran = this.runDeferred();
    assertEquals(1, ran);
    verify(this.jda).awaitReady();
    final boolean ready = this.provider.isDiscordBotReady();
    assertTrue(ready);
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    assertPlaysInto(this.discord, filter);
  }

  @Test
  void shutsTheBotDownWhenItIsNotAMemberOfTheGuild() {
    this.enableDiscord();
    when(this.jda.getGuildById("100")).thenReturn(null);
    this.provider.initialize();
    verify(this.jda).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
    final String message = this.discordFailure();
    assertEquals("The Discord bot is not ready", message);
    this.provider.shutdown();
    verify(this.jda, never()).shutdown();
  }

  @Test
  void shutsTheBotDownWhenTheVoiceChannelDoesNotExist() {
    this.enableDiscord();
    when(this.guild.getVoiceChannelById("200")).thenReturn(null);
    this.provider.initialize();
    verify(this.jda).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
    this.discordPlayers.verifyNoInteractions();
  }

  @Test
  void shutsTheBotDownAndKeepsTheInterruptWhenInterruptedWhileLoggingIn() throws InterruptedException {
    this.enableDiscord();
    doThrow(new InterruptedException("stop")).when(this.jda).awaitReady();
    this.provider.initialize();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    verify(this.jda).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
  }

  @Test
  void startsTheWebPageAndShowsTheMediaOnIt() {
    this.enableHttp();
    when(this.httpServer.getFullUrl()).thenReturn("http://mc.example.com:3000/");
    this.provider.initialize();
    verify(this.httpServer).start();
    final boolean ready = this.provider.isHttpReady();
    assertTrue(ready);
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    assertPlaysInto(this.httpServer, filter);
    verify(this.httpServer).setCurrentMedia(this.dump);
  }

  @Test
  void startsTheWebPageWithoutMakingTheCallerWait() {
    this.startInTheBackground();
    this.enableHttp();
    this.provider.initialize();
    verify(this.httpServer, never()).start();
    final boolean readyWhileStarting = this.provider.isHttpReady();
    assertFalse(readyWhileStarting);
    final String message = this.httpFailure();
    assertEquals("The audio web page is not ready", message);
    final int ran = this.runDeferred();
    assertEquals(1, ran);
    verify(this.httpServer).start();
    final boolean ready = this.provider.isHttpReady();
    assertTrue(ready);
  }

  @Test
  void leavesTheWebPageUnavailableWhenItFailsToStart() {
    this.enableHttp();
    doThrow(new IllegalStateException("port 3000 is in use")).when(this.httpServer).start();
    this.provider.initialize();
    final boolean ready = this.provider.isHttpReady();
    assertFalse(ready);
    final String message = this.httpFailure();
    assertEquals("The audio web page is not ready", message);
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
  }

  @Test
  void refusesOutputsThatWereNotStarted() {
    final String httpMessage = this.httpFailure();
    final String discordMessage = this.discordFailure();
    assertEquals("The audio web page is not ready", httpMessage);
    assertEquals("The Discord bot is not ready", discordMessage);
  }

  @Test
  void playsNoAudioForTheNoneOutput() {
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players);
    assertSame(AudioFilter.NO_OP, filter);
  }

  @Test
  void registersForTheServerLoadWhenVoiceChatIsInstalled() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
    final PluginManager pluginManager = TestServer.pluginManager();
    when(pluginManager.isPluginEnabled("voicechat")).thenReturn(true);
    this.provider.initialize();
    verify(pluginManager).registerEvents(any(ServerLoadListener.class), eq(this.sandbox));
  }

  @Test
  void failsWhenVoiceChatIsEnabledButNotInstalled() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.provider::initialize);
    final String message = exception.getMessage();
    assertEquals("Simple Voice Chat audio is enabled, but the voicechat plugin is not installed", message);
    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager, never()).registerEvents(any(), any());
  }

  private static void assertPlaysInto(final AudioFilter output, final AudioFilter filter) {
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("pcm_s16le", 1_536_000, 48_000, 2, 1);
    when(output.applyFilter(samples, metadata)).thenReturn(true);
    assertTrue(filter.applyFilter(samples, metadata));
    verify(output).applyFilter(samples, metadata);
  }

  @Test
  void replacingTheSameSourcesSpeakersReleasesThePreviousFilter() {
    final Object source = new Object();
    final Object[] replacementPlayers = { "Alex" };
    final SVCFilter replacementSpeakers = mock(SVCFilter.class);
    this.svcFilters.when(() -> SVCFilter.svc(replacementPlayers)).thenReturn(replacementSpeakers);
    final AudioFilter previous = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, source);
    final AudioFilter replacement = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, replacementPlayers, source);
    final InOrder ownership = inOrder(this.voiceChatFilter, replacementSpeakers);
    ownership.verify(this.voiceChatFilter).start();
    ownership.verify(replacementSpeakers).start();
    ownership.verify(this.voiceChatFilter).release();
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("pcm_s16le", 1_536_000, 48_000, 2, 1);
    assertFalse(previous.applyFilter(samples, metadata));
    assertPlaysInto(replacementSpeakers, replacement);
    this.provider.releaseAudioFilter(source);
    verify(this.voiceChatFilter, times(1)).release();
    verify(replacementSpeakers, times(1)).release();
    assertFalse(replacement.applyFilter(samples, metadata));
  }

  @Test
  void theOutputsPlayOneSourceAtATime() {
    final SVCFilter videoSpeakers = this.voiceChatFilter;
    final SVCFilter machineSpeakers = mock(SVCFilter.class);
    final SVCFilter replacementSpeakers = mock(SVCFilter.class);
    this.svcFilters.when(() -> SVCFilter.svc(this.players)).thenReturn(videoSpeakers, machineSpeakers, replacementSpeakers);
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    final AudioFilter sound = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    // the machine took the outputs over: the speakers of the video stop, and its filter falls silent
    verify(videoSpeakers).release();
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of("pcm_s16le", 1_536_000, 48_000, 2, 1);
    assertFalse(video.applyFilter(samples, metadata));
    verify(videoSpeakers, never()).applyFilter(any(), any());
    assertPlaysInto(machineSpeakers, sound);
    assertFalse(sound.applyFilter(ByteBuffer.allocate(8), metadata), "what the output answers comes back");
    // the real factory creates fresh speakers on every request, including the same source
    final AudioFilter replacement = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    verify(machineSpeakers).release();
    verify(replacementSpeakers, never()).release();
    assertFalse(sound.applyFilter(samples, metadata));
    assertPlaysInto(replacementSpeakers, replacement);
    // releasing the video leaves the machine playing; releasing the machine lets go of the outputs
    this.provider.releaseAudioFilter();
    verify(replacementSpeakers, never()).release();
    this.provider.releaseAudioFilter(new Object());
    verify(replacementSpeakers, never()).release();
    this.provider.releaseAudioFilter(machine);
    verify(machineSpeakers, times(1)).release();
    verify(replacementSpeakers, times(1)).release();
    assertFalse(replacement.applyFilter(samples, metadata));
    assertFalse(sound.applyFilter(samples, metadata), "a released source plays no more");
    assertThrows(NullPointerException.class, () -> this.provider.releaseAudioFilter(null));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players, null));
    assertSame(AudioFilter.NO_OP, this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players, machine));
  }

  @Test
  void theWebPageGoesBackToTheVideoWhenTheMachineIsReleased() {
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, mock(URLParseDump.class), this.players, machine);
    this.provider.releaseAudioFilter(machine);
    assertPlaysInto(this.httpServer, video);
    verify(this.httpServer, times(2)).setCurrentMedia(this.dump);
  }

  @Test
  void theBotGoesBackToTheVideoWhenTheMachineIsReleased() {
    this.enableDiscord();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.DISCORD_BOT, mock(URLParseDump.class), this.players, machine);
    this.provider.releaseAudioFilter(machine);
    assertPlaysInto(this.discord, video);
    // the video, the machine, and the video again
    verify(this.audioManager, times(3)).openAudioConnection(this.channel);
    verify(this.discord, times(2)).setCurrentMedia(this.dump);
  }

  @Test
  void theVideoGetsSpeakersAgainWhenTheMachineIsReleased() {
    final SVCFilter videoSpeakers = this.voiceChatFilter;
    final SVCFilter machineSpeakers = mock(SVCFilter.class);
    final SVCFilter videoSpeakersAgain = mock(SVCFilter.class);
    this.svcFilters.when(() -> SVCFilter.svc(this.players)).thenReturn(videoSpeakers, machineSpeakers, videoSpeakersAgain);
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    this.provider.releaseAudioFilter(machine);
    verify(machineSpeakers).release();
    verify(videoSpeakersAgain).start();
    assertPlaysInto(videoSpeakersAgain, video);
  }

  @Test
  void aVideoStoppedWhileTheMachinePlayedDoesNotGetTheOutputsBack() {
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, mock(URLParseDump.class), this.players, machine);
    this.provider.releaseAudioFilter();
    this.provider.releaseAudioFilter(machine);
    assertFalse(video.applyFilter(ByteBuffer.allocate(4), OriginalAudioMetadata.of("pcm_s16le", 1_536_000, 48_000, 2, 1)));
    verify(this.httpServer).setCurrentMedia(MediaInfo.EMPTY);
    verify(this.httpServer, times(1)).setCurrentMedia(this.dump);
  }

  @Test
  void theOutputsStayFreeWhenTheSourceBeforeCannotHaveThemBack() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, mock(URLParseDump.class), this.players, machine);
    // the bot lost the right to join its channel meanwhile
    doThrow(new IllegalStateException("Missing permission VOICE_CONNECT")).when(this.audioManager).openAudioConnection(this.channel);
    this.provider.releaseAudioFilter(machine);
    assertFalse(video.applyFilter(ByteBuffer.allocate(4), OriginalAudioMetadata.of("pcm_s16le", 1_536_000, 48_000, 2, 1)));
    verify(this.httpServer).setCurrentMedia(MediaInfo.EMPTY);
    // the video gave the outputs up with the failure; its own release later finds them free
    this.provider.releaseAudioFilter();
    verify(this.httpServer, times(2)).setCurrentMedia(MediaInfo.EMPTY);
  }

  @Test
  void aTakeoverThatFailsLeavesTheSourceBeforePlayingThroughItsSpeakers() {
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    // the machine chose the bot, which is not ready, so it cannot take the outputs over
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players, machine)
    );
    assertEquals("The Discord bot is not ready", failure.getMessage());
    this.provider.releaseAudioFilter(machine);
    verify(this.voiceChatFilter, never()).release();
    assertPlaysInto(this.voiceChatFilter, video);
  }

  @Test
  void theSpeakersOfTheSourceBeforeStopWhenTheNewSourcePlaysOnTheWebPage() {
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    final AudioFilter sound = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players, machine);
    verify(this.voiceChatFilter).release();
    assertPlaysInto(this.httpServer, sound);
    // the stopped speakers are no output of the machine's: its release does not stop them again
    this.provider.releaseAudioFilter(machine);
    verify(this.voiceChatFilter, times(1)).release();
  }

  @Test
  void aSourceThatChoseAgainIsNeverHandedItsEarlierChoiceBack() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final Object browser = new Object();
    // the video chose the web page, then the bot: its second choice replaced the first
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    // the bot lost the right to join its channel meanwhile, so the video gives the outputs up
    doThrow(new IllegalStateException("Missing permission VOICE_CONNECT")).when(this.audioManager).openAudioConnection(this.channel);
    this.provider.releaseAudioFilter(machine);
    // a browser that plays and is released later hands the web page back to no one
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, browser);
    this.provider.releaseAudioFilter(browser);
    verify(this.httpServer, times(1)).setCurrentMedia(this.dump);
  }

  @Test
  void aSourceReleasedAfterTheShutdownStartsNoOutputAgain() {
    final SVCFilter lateSpeakers = mock(SVCFilter.class);
    this.svcFilters.when(() -> SVCFilter.svc(this.players)).thenReturn(this.voiceChatFilter, this.voiceChatFilter, lateSpeakers);
    final Object machine = new Object();
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    this.provider.shutdown();
    // a machine whose release ends after the shutdown: the video it took the outputs from no longer waits for them
    this.provider.releaseAudioFilter(machine);
    verify(lateSpeakers, never()).start();
    this.svcFilters.verify(() -> SVCFilter.svc(this.players), times(2));
  }

  @Test
  void playsThroughVoiceChatSpeakersUntilReleased() {
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    assertPlaysInto(this.voiceChatFilter, filter);
    verify(this.voiceChatFilter).start();
    this.provider.releaseAudioFilter();
    this.provider.releaseAudioFilter();
    verify(this.voiceChatFilter, times(1)).release();
  }

  @Test
  void disconnectsEveryOutputWhenTheVideoIsReleased() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.releaseAudioFilter();
    verify(this.audioManager).setSendingHandler(null);
    verify(this.audioManager).closeAudioConnection();
    verify(this.discord).flush();
    verify(this.httpServer).setCurrentMedia(MediaInfo.EMPTY);
    verify(this.httpServer, never()).stop();
    verify(this.jda, never()).shutdown();
  }

  @Test
  void releasesNothingWhenNothingWasStarted() {
    this.provider.releaseAudioFilter();
    this.provider.shutdown();
    verifyNoInteractions(this.audioManager, this.discord, this.httpServer, this.voiceChatFilter, this.jda);
    final boolean stopped = this.startup.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void stopsEveryOutputOnceWhenShutDown() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.shutdown();
    this.provider.shutdown();
    verify(this.httpServer, times(1)).stop();
    verify(this.jda, times(1)).shutdown();
    verify(this.audioManager, times(1)).closeAudioConnection();
    final String message = this.discordFailure();
    assertEquals("The Discord bot is not ready", message);
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(httpReady);
    final String url = this.provider.constructHttpUrl();
    assertEquals("http://mc.example.com:3000/", url);
    final boolean stopped = this.startup.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void waitsForInterruptedHttpStartupToCloseBeforeReturningFromShutdown() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final CountDownLatch finishStartup = new CountDownLatch(1);
    this.startup.shutdownNow();
    this.startup = Executors.newSingleThreadExecutor();
    this.provider = new AudioProvider(this.sandbox, this.startup);
    this.enableHttp();
    doAnswer(_ -> {
      entered.countDown();
      try {
        assertTrue(finishStartup.await(10, TimeUnit.SECONDS));
      } catch (final InterruptedException exception) {
        interrupted.countDown();
        assertTrue(finishStartup.await(10, TimeUnit.SECONDS));
      }
      return null;
    })
      .when(this.httpServer)
      .start();
    this.provider.initialize();
    final ExecutorService stopping = Executors.newSingleThreadExecutor();
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      final Future<?> shutdown = stopping.submit(this.provider::shutdown);
      try {
        assertTrue(interrupted.await(10, TimeUnit.SECONDS));
        assertThrows(
          TimeoutException.class,
          () -> shutdown.get(1, TimeUnit.SECONDS),
          "the plugin loader must stay open until startup releases its server"
        );
      } finally {
        finishStartup.countDown();
        shutdown.get(10, TimeUnit.SECONDS);
      }
      assertTrue(this.startup.awaitTermination(10, TimeUnit.SECONDS));
      verify(this.httpServer).stop();
      assertFalse(this.provider.isHttpReady());
    } finally {
      finishStartup.countDown();
      this.startup.shutdownNow();
      stopping.shutdownNow();
      assertTrue(this.startup.awaitTermination(10, TimeUnit.SECONDS));
      assertTrue(stopping.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void reportsStartupThatDoesNotTerminateBeforeTheShutdownDeadline() throws InterruptedException {
    this.startInTheBackground();
    when(this.startup.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(false);
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.provider.shutdown();
      output = errors.getOutput();
    }
    verify(this.startup).shutdownNow();
    verify(this.startup).awaitTermination(10, TimeUnit.SECONDS);
    assertTrue(output.contains("Audio startup did not stop within 10 seconds"), output);
  }

  @Test
  void preservesTheInterruptWhenShutdownCannotWaitForStartup() throws InterruptedException {
    this.startInTheBackground();
    when(this.startup.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("stop waiting"));
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.provider.shutdown();
      output = errors.getOutput();
    }
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    verify(this.startup).shutdownNow();
    assertTrue(output.contains("Interrupted while waiting for audio startup to stop"), output);
  }

  @Test
  void stopsOutputsThatFinishStartingAfterTheShutdown() throws InterruptedException {
    this.startInTheBackground();
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.shutdown();
    verify(this.jda).shutdown();
    verify(this.startup).shutdownNow();
    verify(this.httpServer, never()).stop();
    final int ran = this.runDeferred();
    assertEquals(2, ran);
    verify(this.jda).awaitReady();
    verify(this.httpServer).start();
    verify(this.httpServer).stop();
    final boolean discordReady = this.provider.isDiscordBotReady();
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(discordReady);
    assertFalse(httpReady);
    verify(this.jda, never()).shutdownNow();
  }

  @Test
  void doesNotShutDownABotTwiceWhenItFailsAfterTheShutdown() {
    this.startInTheBackground();
    this.enableDiscord();
    when(this.jda.getGuildById("100")).thenReturn(null);
    this.provider.initialize();
    this.provider.shutdown();
    this.runDeferred();
    verify(this.jda, times(1)).shutdown();
    verify(this.jda, never()).shutdownNow();
    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
  }
}
