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
package me.brandonli.mcav.plugin.audio;

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
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.brandonli.mcav.discord.DiscordPlayer;
import me.brandonli.mcav.http.HttpResult;
import me.brandonli.mcav.http.MediaInformation;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.voicechat.SvcFilter;
import me.brandonli.mcav.voicechat.SvcModule;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

// most tests start the outputs on the calling thread, so they are ready when initialize returns
final class AudioProviderTest {

  private static final int HTTP_PORT = 3000;
  private static final String HTTP_SERVER_URL = "http://mc.example.com:3000/";
  private static final String HTTP_HOST_NAME = "mc.example.com";

  private static final String GUILD_ID = "100";
  private static final String CHANNEL_ID = "200";
  private static final String BOT_TOKEN = "token";

  private static final String DISCORD_NOT_READY_MESSAGE = "The Discord bot is not ready!";
  private static final String WEB_PAGE_NOT_READY_MESSAGE = "The audio web page is not ready!";

  private static final String PORT_IN_USE_MESSAGE = "port 3000 is in use!";

  private static final String DISCORD_FAILURE_LOG = "The Discord bot could not connect, so its audio is not available.";

  private static final int BIT_RATE = 1_536_000;
  private static final int SAMPLE_RATE = 48_000;
  private static final int SAMPLE_BUFFER_BYTES = 4;
  private static final int AUDIO_CHANNEL_COUNT = 2;
  private static final String AUDIO_CODEC = "pcm_s16le";

  private static final int SHUTDOWN_DEADLINE_SECONDS = 10;

  private static final String MACHINE_TITLE = "The machine";

  private static final String MISSING_VOICE_CONNECT_PERMISSION_MESSAGE = "Missing permission VOICE_CONNECT!";

  private final UrlParseDump dump = GsonProvider.getSimple().fromJson("{}", UrlParseDump.class);
  private final Object[] players = { "Steve" };
  private final List<Runnable> deferred = new ArrayList<>();

  private MCAVSandbox sandbox;
  private PluginDataConfigurationMapper configuration;
  private ExecutorService startup;
  private AudioProvider provider;
  private MockedStatic<JDABuilder> builders;
  private MockedStatic<DiscordPlayer> discordPlayers;
  private MockedStatic<HttpResult> httpResults;
  private MockedStatic<SvcFilter> svcFilters;

  private JDABuilder builder;
  private JDA jda;
  private Guild guild;
  private VoiceChannel channel;
  private AudioManager audioManager;
  private DiscordPlayer discord;
  private HttpResult httpServer;
  private SvcFilter voiceChatFilter;

  @BeforeEach
  void createProvider() {
    TestServer.reset();
    this.sandbox = mock(MCAVSandbox.class);
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(this.sandbox.getConfiguration()).thenReturn(this.configuration);
    when(this.configuration.getDiscordBotToken()).thenReturn(BOT_TOKEN);
    when(this.configuration.getDiscordBotGuildId()).thenReturn(GUILD_ID);
    when(this.configuration.getDiscordBotChannelId()).thenReturn(CHANNEL_ID);
    when(this.configuration.getHttpHostName()).thenReturn(HTTP_HOST_NAME);
    when(this.configuration.getHttpPort()).thenReturn(HTTP_PORT);
    this.startup = MoreExecutors.newDirectExecutorService();
    this.provider = new AudioProvider(this.sandbox, this.startup);
    this.mockOutputs();
  }

  @AfterEach
  void closeStaticMocks() {
    this.builders.close();
    this.discordPlayers.close();
    this.httpResults.close();
    this.svcFilters.close();
    this.startup.shutdownNow();
  }

  private void mockOutputs() {
    this.builder = mock(JDABuilder.class);
    this.jda = mock(JDA.class);
    this.guild = mock(Guild.class);
    this.channel = mock(VoiceChannel.class);
    this.audioManager = mock(AudioManager.class);
    this.discord = mock(DiscordPlayer.class);
    this.httpServer = mock(HttpResult.class);
    this.voiceChatFilter = mock(SvcFilter.class);
    when(this.builder.build()).thenReturn(this.jda);
    when(this.jda.getGuildById(GUILD_ID)).thenReturn(this.guild);
    when(this.guild.getVoiceChannelById(CHANNEL_ID)).thenReturn(this.channel);
    when(this.guild.getAudioManager()).thenReturn(this.audioManager);
    this.builders = Mockito.mockStatic(JDABuilder.class);
    this.builders.when(this::createLightBuilder).thenReturn(this.builder);
    this.discordPlayers = Mockito.mockStatic(DiscordPlayer.class);
    this.discordPlayers.when(() -> DiscordPlayer.voice(this.jda)).thenReturn(this.discord);
    this.httpResults = Mockito.mockStatic(HttpResult.class);
    this.httpResults.when(() -> HttpResult.http(HTTP_HOST_NAME, HTTP_PORT)).thenReturn(this.httpServer);
    this.svcFilters = Mockito.mockStatic(SvcFilter.class);
    this.svcFilters.when(() -> SvcFilter.svc(this.players)).thenReturn(this.voiceChatFilter);
  }

  private void createLightBuilder() {
    // JDA requires the result of createLight to be used, so the stubbed call assigns it to an unnamed variable
    final JDABuilder _ = JDABuilder.createLight(BOT_TOKEN, GatewayIntent.GUILD_VOICE_STATES);
  }

  @Test
  void shouldRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> new AudioProvider(null));
    assertThrows(NullPointerException.class, () -> new AudioProvider(this.sandbox, null));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(null, this.dump, this.players));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, null, this.players));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, this.dump, null));
  }

  @Test
  void shouldLinkToTheConfiguredVoiceChannel() {
    final String url = this.provider.constructVoiceChannelUrl();
    assertEquals("https://discord.com/channels/100/200", url);
  }

  @Test
  void shouldLinkToTheConfiguredWebPageBeforeItStarts() {
    final String url = this.provider.constructHttpUrl();
    assertEquals(HTTP_SERVER_URL, url);
  }

  @Test
  void shouldReportWhichOutputsAreEnabled() {
    final boolean discordEnabledBefore = this.provider.isDiscordBotEnabled();
    final boolean httpEnabledBefore = this.provider.isHttpEnabled();
    final boolean voiceChatEnabledBefore = this.provider.isSimpleVoiceChatEnabled();
    assertFalse(discordEnabledBefore);
    assertFalse(httpEnabledBefore);
    assertFalse(voiceChatEnabledBefore);

    this.enableDiscord();
    this.enableHttp();
    this.enableVoiceChat();
    final boolean discordEnabledAfter = this.provider.isDiscordBotEnabled();
    final boolean httpEnabledAfter = this.provider.isHttpEnabled();
    final boolean voiceChatEnabledAfter = this.provider.isSimpleVoiceChatEnabled();
    assertTrue(discordEnabledAfter);
    assertTrue(httpEnabledAfter);
    assertTrue(voiceChatEnabledAfter);
  }

  private void enableDiscord() {
    when(this.configuration.isDiscordBotEnabled()).thenReturn(true);
  }

  private void enableHttp() {
    when(this.configuration.isHttpEnabled()).thenReturn(true);
  }

  private void enableVoiceChat() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
  }

  @Test
  void shouldBeReadyForVoiceChatOnlyWhileTheLibraryHoldsItsServer() {
    final SvcModule module = new SvcModule();
    module.stop();

    final boolean readyBefore = this.provider.isSimpleVoiceChatReady();
    assertFalse(readyBefore);

    final VoicechatServerApi api = mock(VoicechatServerApi.class);
    module.inject(api);
    try {
      final boolean ready = this.provider.isSimpleVoiceChatReady();
      assertTrue(ready);
    } finally {
      module.stop();
    }
  }

  @Test
  void shouldStartNothingWhenEveryOutputIsDisabled() {
    this.provider.initialize();
    this.builders.verifyNoInteractions();
    this.httpResults.verifyNoInteractions();

    final PluginManager pluginManager = TestServer.getPluginManager();
    verifyNoInteractions(pluginManager);

    final boolean discordReady = this.provider.isDiscordBotReady();
    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(discordReady);
    assertFalse(httpReady);
  }

  @Test
  void shouldLogTheBotInAndPlayIntoTheConfiguredChannel() throws InterruptedException {
    this.enableDiscord();
    this.provider.initialize();
    verify(this.builder).enableCache(CacheFlag.VOICE_STATE);
    verify(this.jda).awaitReady();

    final boolean ready = this.provider.isDiscordBotReady();
    assertTrue(ready);

    final AudioFilter filter = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.assertPlaysInto(this.discord, filter);

    final InOrder order = inOrder(this.discord, this.audioManager);
    order.verify(this.discord).flush();
    order.verify(this.audioManager).setSendingHandler(this.discord);
    order.verify(this.audioManager).openAudioConnection(this.channel);
    order.verify(this.discord).setCurrentMedia(this.dump);
  }

  @Test
  void shouldLogTheBotInWithoutMakingTheCallerWait() throws InterruptedException {
    this.startInTheBackground();
    this.enableDiscord();
    this.provider.initialize();
    verify(this.builder).build();
    verify(this.jda, never()).awaitReady();

    final boolean readyWhileStarting = this.provider.isDiscordBotReady();
    assertFalse(readyWhileStarting);

    final String message = this.discordFailure();
    assertEquals(DISCORD_NOT_READY_MESSAGE, message);

    final int ran = this.runDeferred();
    assertEquals(1, ran);
    verify(this.jda).awaitReady();

    final boolean ready = this.provider.isDiscordBotReady();
    assertTrue(ready);

    final AudioFilter filter = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.assertPlaysInto(this.discord, filter);
  }

  private void startInTheBackground() {
    final ExecutorService executor = mock(ExecutorService.class);
    doAnswer(this::deferTask).when(executor).execute(any(Runnable.class));
    this.startup = executor;
    this.provider = new AudioProvider(this.sandbox, executor);
  }

  private @Nullable Object deferTask(final InvocationOnMock invocation) {
    final Runnable task = invocation.getArgument(0);
    this.deferred.add(task);

    return null;
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

  @Test
  void shouldShutTheBotDownWhenItIsNotAMemberOfTheGuild() {
    this.enableDiscord();
    when(this.jda.getGuildById(GUILD_ID)).thenReturn(null);
    this.provider.initialize();
    verify(this.jda).shutdownNow();

    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);

    final String message = this.discordFailure();
    assertEquals(DISCORD_NOT_READY_MESSAGE, message);

    this.provider.shutdown();
    verify(this.jda, never()).shutdown();
  }

  @Test
  void shouldShutTheBotDownWhenTheVoiceChannelDoesNotExist() {
    this.enableDiscord();
    when(this.guild.getVoiceChannelById(CHANNEL_ID)).thenReturn(null);
    this.provider.initialize();
    verify(this.jda).shutdownNow();

    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
    this.discordPlayers.verifyNoInteractions();
  }

  @Test
  void shouldLogWhenTheGuildHasNotAddedTheBot() {
    this.enableDiscord();
    when(this.jda.getGuildById(GUILD_ID)).thenReturn(null);
    final String output = this.initializeCapturingErrors();
    final boolean reported = output.contains(DISCORD_FAILURE_LOG);
    final boolean explained = output.contains("Guild 100 has not added the Discord bot!");
    assertTrue(reported, output);
    assertTrue(explained, output);
  }

  @Test
  void shouldLogWhenTheVoiceChannelDoesNotExist() {
    this.enableDiscord();
    when(this.guild.getVoiceChannelById(CHANNEL_ID)).thenReturn(null);
    final String output = this.initializeCapturingErrors();
    final boolean reported = output.contains(DISCORD_FAILURE_LOG);
    final boolean explained = output.contains("Discord voice channel 200 does not exist!");
    assertTrue(reported, output);
    assertTrue(explained, output);
  }

  private String initializeCapturingErrors() {
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.provider.initialize();
      return errors.getOutput();
    }
  }

  @Test
  void shouldShutTheBotDownAndKeepTheInterruptWhenInterruptedWhileLoggingIn() throws InterruptedException {
    this.enableDiscord();
    doThrow(new InterruptedException("stop!")).when(this.jda).awaitReady();
    this.provider.initialize();
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    verify(this.jda).shutdownNow();

    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
  }

  @Test
  void shouldStartTheWebPageAndShowTheMediaOnIt() {
    this.enableHttp();
    when(this.httpServer.getFullUrl()).thenReturn(HTTP_SERVER_URL);
    this.provider.initialize();
    verify(this.httpServer).start();

    final boolean ready = this.provider.isHttpReady();
    assertTrue(ready);

    final String url = this.provider.constructHttpUrl();
    assertEquals(HTTP_SERVER_URL, url);

    final AudioFilter filter = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.assertPlaysInto(this.httpServer, filter);
    verify(this.httpServer).setCurrentMedia(this.dump);
  }

  @Test
  void shouldStartTheWebPageWithoutMakingTheCallerWait() {
    this.startInTheBackground();
    this.enableHttp();
    this.provider.initialize();
    verify(this.httpServer, never()).start();

    final boolean readyWhileStarting = this.provider.isHttpReady();
    assertFalse(readyWhileStarting);

    final String message = this.httpFailure();
    assertEquals(WEB_PAGE_NOT_READY_MESSAGE, message);

    final int ran = this.runDeferred();
    assertEquals(1, ran);
    verify(this.httpServer).start();

    final boolean ready = this.provider.isHttpReady();
    assertTrue(ready);
  }

  private String httpFailure() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players)
    );

    return exception.getMessage();
  }

  @Test
  void shouldLeaveTheWebPageUnavailableWhenItFailsToStart() {
    this.enableHttp();
    doThrow(new IllegalStateException(PORT_IN_USE_MESSAGE)).when(this.httpServer).start();
    this.provider.initialize();
    final boolean ready = this.provider.isHttpReady();
    assertFalse(ready);

    final String message = this.httpFailure();
    assertEquals(WEB_PAGE_NOT_READY_MESSAGE, message);

    final String url = this.provider.constructHttpUrl();
    assertEquals(HTTP_SERVER_URL, url);
  }

  @Test
  void shouldLogWhyTheWebPageCouldNotStart() {
    this.enableHttp();
    doThrow(new IllegalStateException(PORT_IN_USE_MESSAGE)).when(this.httpServer).start();
    final String output = this.initializeCapturingErrors();
    final boolean reported = output.contains("The audio web page could not be started, so its audio is not available.");
    final boolean explained = output.contains(PORT_IN_USE_MESSAGE);
    assertTrue(reported, output);
    assertTrue(explained, output);
  }

  @Test
  void shouldRefuseOutputsThatWereNotStarted() {
    final String httpMessage = this.httpFailure();
    final String discordMessage = this.discordFailure();
    assertEquals(WEB_PAGE_NOT_READY_MESSAGE, httpMessage);
    assertEquals(DISCORD_NOT_READY_MESSAGE, discordMessage);
  }

  @Test
  void shouldPlayNoAudioForTheNoneOutput() {
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players);
    assertSame(AudioFilter.NO_OPERATION, filter);
  }

  @Test
  void shouldRegisterForTheServerLoadWhenVoiceChatIsInstalled() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
    final PluginManager pluginManager = TestServer.getPluginManager();
    when(pluginManager.isPluginEnabled("voicechat")).thenReturn(true);
    this.provider.initialize();
    verify(pluginManager).registerEvents(any(ServerLoadListener.class), eq(this.sandbox));
  }

  @Test
  void shouldFailWhenVoiceChatIsEnabledButNotInstalled() {
    when(this.configuration.isSimpleVoiceChatEnabled()).thenReturn(true);
    final MissingVoiceChatException exception = assertThrows(MissingVoiceChatException.class, this.provider::initialize);

    final String message = exception.getMessage();
    assertEquals("Simple Voice Chat audio is enabled, but the Simple Voice Chat plugin is not installed!", message);

    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager, never()).registerEvents(any(), any());
  }

  private void assertPlaysInto(final AudioFilter output, final AudioFilter filter) {
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(AUDIO_CODEC, BIT_RATE, SAMPLE_RATE, AUDIO_CHANNEL_COUNT, 1);
    when(output.applyFilter(samples, metadata)).thenReturn(true);

    final boolean filtered = filter.applyFilter(samples, metadata);
    assertTrue(filtered);

    verify(output).applyFilter(samples, metadata);
  }

  @Test
  void shouldReleaseThePreviousFilterWhenReplacingTheSameSourcesSpeakers() {
    final Object source = new Object();
    final Object[] replacementPlayers = { "Alex" };
    final SvcFilter replacementSpeakers = mock(SvcFilter.class);
    this.svcFilters.when(() -> SvcFilter.svc(replacementPlayers)).thenReturn(replacementSpeakers);
    final AudioFilter previous = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, source);
    final AudioFilter replacement = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, replacementPlayers, source);
    final InOrder ownership = inOrder(this.voiceChatFilter, replacementSpeakers);
    ownership.verify(this.voiceChatFilter).start();
    ownership.verify(replacementSpeakers).start();
    ownership.verify(this.voiceChatFilter).release();

    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(AUDIO_CODEC, BIT_RATE, SAMPLE_RATE, AUDIO_CHANNEL_COUNT, 1);

    final boolean filtered = previous.applyFilter(samples, metadata);
    assertFalse(filtered);
    this.assertPlaysInto(replacementSpeakers, replacement);

    this.provider.releaseAudioFilter(source);
    verify(this.voiceChatFilter, times(1)).release();
    verify(replacementSpeakers, times(1)).release();

    final boolean replacementFiltered = replacement.applyFilter(samples, metadata);
    assertFalse(replacementFiltered);
  }

  @Test
  void shouldPlayOneSourceAtATimeOnTheOutputs() {
    final SvcFilter videoSpeakers = this.voiceChatFilter;
    final SvcFilter machineSpeakers = mock(SvcFilter.class);
    final SvcFilter replacementSpeakers = mock(SvcFilter.class);
    this.svcFilters.when(() -> SvcFilter.svc(this.players)).thenReturn(videoSpeakers, machineSpeakers, replacementSpeakers);
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    final AudioFilter sound = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    verify(videoSpeakers).release();

    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES);
    final OriginalAudioMetadata metadata = OriginalAudioMetadata.of(AUDIO_CODEC, BIT_RATE, SAMPLE_RATE, AUDIO_CHANNEL_COUNT, 1);

    final boolean filtered = video.applyFilter(samples, metadata);
    assertFalse(filtered);

    verify(videoSpeakers, never()).applyFilter(any(), any());
    this.assertPlaysInto(machineSpeakers, sound);

    final boolean soundFiltered = sound.applyFilter(ByteBuffer.allocate(8), metadata);
    assertFalse(soundFiltered, "what the output answers comes back");

    final AudioFilter replacement = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    verify(machineSpeakers).release();
    verify(replacementSpeakers, never()).release();

    final boolean secondFiltered = sound.applyFilter(samples, metadata);
    assertFalse(secondFiltered);
    this.assertPlaysInto(replacementSpeakers, replacement);

    this.provider.releaseAudioFilter();
    verify(replacementSpeakers, never()).release();

    this.provider.releaseAudioFilter(new Object());
    verify(replacementSpeakers, never()).release();

    this.provider.releaseAudioFilter(machine);
    verify(machineSpeakers, times(1)).release();
    verify(replacementSpeakers, times(1)).release();

    final boolean replacementFiltered = replacement.applyFilter(samples, metadata);
    assertFalse(replacementFiltered);

    final boolean thirdFiltered = sound.applyFilter(samples, metadata);
    assertFalse(thirdFiltered, "a released source plays no more");
    assertThrows(NullPointerException.class, () -> this.provider.releaseAudioFilter(null));
    assertThrows(NullPointerException.class, () -> this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players, null));

    final AudioFilter constructFilter = this.provider.constructFilter(AudioArgument.NONE, this.dump, this.players, machine);
    assertSame(AudioFilter.NO_OPERATION, constructFilter);
  }

  @Test
  void shouldGiveTheWebPageBackToTheVideoWhenTheMachineIsReleased() {
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, UrlParseDump.of(MACHINE_TITLE), this.players, machine);
    this.provider.releaseAudioFilter(machine);
    this.assertPlaysInto(this.httpServer, video);
    verify(this.httpServer, times(2)).setCurrentMedia(this.dump);
  }

  @Test
  void shouldGiveTheBotBackToTheVideoWhenTheMachineIsReleased() {
    this.enableDiscord();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.DISCORD_BOT, UrlParseDump.of(MACHINE_TITLE), this.players, machine);
    this.provider.releaseAudioFilter(machine);
    this.assertPlaysInto(this.discord, video);
    verify(this.audioManager, times(3)).openAudioConnection(this.channel);
    verify(this.discord, times(2)).setCurrentMedia(this.dump);
  }

  @Test
  void shouldGiveTheVideoSpeakersAgainWhenTheMachineIsReleased() {
    final SvcFilter videoSpeakers = this.voiceChatFilter;
    final SvcFilter machineSpeakers = mock(SvcFilter.class);
    final SvcFilter videoSpeakersAgain = mock(SvcFilter.class);
    this.svcFilters.when(() -> SvcFilter.svc(this.players)).thenReturn(videoSpeakers, machineSpeakers, videoSpeakersAgain);
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    this.provider.releaseAudioFilter(machine);
    verify(machineSpeakers).release();
    verify(videoSpeakersAgain).start();
    this.assertPlaysInto(videoSpeakersAgain, video);
  }

  @Test
  void shouldNotGiveTheOutputsBackToAVideoStoppedWhileTheMachinePlayed() {
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, UrlParseDump.of(MACHINE_TITLE), this.players, machine);
    this.provider.releaseAudioFilter();
    this.provider.releaseAudioFilter(machine);

    final boolean filtered = video.applyFilter(
      ByteBuffer.allocate(SAMPLE_BUFFER_BYTES),
      OriginalAudioMetadata.of(AUDIO_CODEC, BIT_RATE, SAMPLE_RATE, AUDIO_CHANNEL_COUNT, 1)
    );
    assertFalse(filtered);

    verify(this.httpServer).setCurrentMedia(MediaInformation.EMPTY);
    verify(this.httpServer, times(1)).setCurrentMedia(this.dump);
  }

  @Test
  void shouldKeepTheOutputsFreeWhenTheSourceBeforeCannotHaveThemBack() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, UrlParseDump.of(MACHINE_TITLE), this.players, machine);
    doThrow(new IllegalStateException(MISSING_VOICE_CONNECT_PERMISSION_MESSAGE)).when(this.audioManager).openAudioConnection(this.channel);
    this.provider.releaseAudioFilter(machine);

    final boolean filtered = video.applyFilter(
      ByteBuffer.allocate(SAMPLE_BUFFER_BYTES),
      OriginalAudioMetadata.of(AUDIO_CODEC, BIT_RATE, SAMPLE_RATE, AUDIO_CHANNEL_COUNT, 1)
    );
    assertFalse(filtered);

    verify(this.httpServer).setCurrentMedia(MediaInformation.EMPTY);

    this.provider.releaseAudioFilter();
    verify(this.httpServer, times(2)).setCurrentMedia(MediaInformation.EMPTY);
  }

  @Test
  void shouldLeaveTheSourceBeforePlayingThroughItsSpeakersWhenATakeoverFails() {
    final Object machine = new Object();
    final AudioFilter video = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
      this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players, machine)
    );

    final String message = failure.getMessage();
    assertEquals(DISCORD_NOT_READY_MESSAGE, message);

    this.provider.releaseAudioFilter(machine);
    verify(this.voiceChatFilter, never()).release();
    this.assertPlaysInto(this.voiceChatFilter, video);
  }

  @Test
  void shouldStopTheSpeakersOfTheSourceBeforeWhenTheNewSourcePlaysOnTheWebPage() {
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    final AudioFilter sound = this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players, machine);
    verify(this.voiceChatFilter).release();
    this.assertPlaysInto(this.httpServer, sound);

    this.provider.releaseAudioFilter(machine);
    verify(this.voiceChatFilter, times(1)).release();
  }

  @Test
  void shouldNeverHandASourceThatChoseAgainItsEarlierChoiceBack() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    final Object machine = new Object();
    final Object browser = new Object();
    this.provider.constructFilter(AudioArgument.HTTP_SERVER, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.DISCORD_BOT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    doThrow(new IllegalStateException(MISSING_VOICE_CONNECT_PERMISSION_MESSAGE)).when(this.audioManager).openAudioConnection(this.channel);
    this.provider.releaseAudioFilter(machine);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, browser);
    this.provider.releaseAudioFilter(browser);
    verify(this.httpServer, times(1)).setCurrentMedia(this.dump);
  }

  @Test
  void shouldStartNoOutputAgainForASourceReleasedAfterTheShutdown() {
    final SvcFilter lateSpeakers = mock(SvcFilter.class);
    this.svcFilters.when(() -> SvcFilter.svc(this.players)).thenReturn(this.voiceChatFilter, this.voiceChatFilter, lateSpeakers);
    final Object machine = new Object();
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players, machine);
    this.provider.shutdown();
    this.provider.releaseAudioFilter(machine);
    verify(lateSpeakers, never()).start();
    this.svcFilters.verify(() -> SvcFilter.svc(this.players), times(2));
  }

  @Test
  void shouldPlayThroughVoiceChatSpeakersUntilReleased() {
    final AudioFilter filter = this.provider.constructFilter(AudioArgument.SIMPLE_VOICE_CHAT, this.dump, this.players);
    this.assertPlaysInto(this.voiceChatFilter, filter);
    verify(this.voiceChatFilter).start();

    this.provider.releaseAudioFilter();
    this.provider.releaseAudioFilter();
    verify(this.voiceChatFilter, times(1)).release();
  }

  @Test
  void shouldDisconnectEveryOutputWhenTheVideoIsReleased() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.releaseAudioFilter();
    verify(this.audioManager).setSendingHandler(null);
    verify(this.audioManager).closeAudioConnection();
    verify(this.discord).flush();
    verify(this.httpServer).setCurrentMedia(MediaInformation.EMPTY);
    verify(this.httpServer, never()).stop();
    verify(this.jda, never()).shutdown();
  }

  @Test
  void shouldReleaseNothingWhenNothingWasStarted() {
    this.provider.releaseAudioFilter();
    this.provider.shutdown();
    verifyNoInteractions(this.audioManager, this.discord, this.httpServer, this.voiceChatFilter, this.jda);

    final boolean stopped = this.startup.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void shouldStopEveryOutputOnceWhenShutDown() {
    this.enableDiscord();
    this.enableHttp();
    this.provider.initialize();
    this.provider.shutdown();
    this.provider.shutdown();
    verify(this.httpServer, times(1)).stop();
    verify(this.jda, times(1)).shutdown();
    verify(this.audioManager, times(1)).closeAudioConnection();

    final String message = this.discordFailure();
    assertEquals(DISCORD_NOT_READY_MESSAGE, message);

    final boolean httpReady = this.provider.isHttpReady();
    assertFalse(httpReady);

    final String url = this.provider.constructHttpUrl();
    assertEquals(HTTP_SERVER_URL, url);

    final boolean stopped = this.startup.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void shouldWaitForInterruptedHttpStartupToCloseBeforeReturningFromShutdown() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final CountDownLatch finishStartup = new CountDownLatch(1);
    this.startup.shutdownNow();
    this.startup = Executors.newSingleThreadExecutor();
    this.provider = new AudioProvider(this.sandbox, this.startup);
    this.enableHttp();
    doAnswer(_ -> this.holdStartupUntilFinished(entered, finishStartup, interrupted))
      .when(this.httpServer)
      .start();
    this.provider.initialize();
    final ExecutorService stopping = Executors.newSingleThreadExecutor();
    try {
      final boolean completed = entered.await(10, TimeUnit.SECONDS);
      assertTrue(completed);

      final Future<?> shutdown = stopping.submit(this.provider::shutdown);
      try {
        final boolean interruptedCompleted = interrupted.await(10, TimeUnit.SECONDS);
        assertTrue(interruptedCompleted);
        assertThrows(
          TimeoutException.class,
          () -> shutdown.get(1, TimeUnit.SECONDS),
          "the plugin loader must stay open until startup releases its server"
        );
      } finally {
        finishStartup.countDown();
        shutdown.get(10, TimeUnit.SECONDS);
      }

      final boolean terminated = this.startup.awaitTermination(10, TimeUnit.SECONDS);
      assertTrue(terminated);

      verify(this.httpServer).stop();

      final boolean httpReady = this.provider.isHttpReady();
      assertFalse(httpReady);
    } finally {
      finishStartup.countDown();
      this.startup.shutdownNow();
      stopping.shutdownNow();

      final boolean startupTerminated = this.startup.awaitTermination(10, TimeUnit.SECONDS);
      assertTrue(startupTerminated);

      final boolean stoppingTerminated = stopping.awaitTermination(10, TimeUnit.SECONDS);
      assertTrue(stoppingTerminated);
    }
  }

  private @Nullable Object holdStartupUntilFinished(
    final CountDownLatch entered,
    final CountDownLatch finishStartup,
    final CountDownLatch interrupted
  ) throws InterruptedException {
    entered.countDown();
    try {
      final boolean startupFinished = finishStartup.await(10, TimeUnit.SECONDS);
      assertTrue(startupFinished);
    } catch (final InterruptedException exception) {
      interrupted.countDown();
      final boolean startupFinished = finishStartup.await(10, TimeUnit.SECONDS);
      assertTrue(startupFinished);
    }
    return null;
  }

  @Test
  void shouldReportStartupThatDoesNotTerminateBeforeTheShutdownDeadline() throws InterruptedException {
    this.startInTheBackground();
    when(this.startup.awaitTermination(SHUTDOWN_DEADLINE_SECONDS, TimeUnit.SECONDS)).thenReturn(false);
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.provider.shutdown();
      output = errors.getOutput();
    }
    verify(this.startup).shutdownNow();
    verify(this.startup).awaitTermination(SHUTDOWN_DEADLINE_SECONDS, TimeUnit.SECONDS);

    final boolean contained = output.contains("Audio startup did not stop within 10 seconds");
    assertTrue(contained, output);
  }

  @Test
  void shouldPreserveTheInterruptWhenShutdownCannotWaitForStartup() throws InterruptedException {
    this.startInTheBackground();
    when(this.startup.awaitTermination(SHUTDOWN_DEADLINE_SECONDS, TimeUnit.SECONDS)).thenThrow(new InterruptedException("stop waiting!"));
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.provider.shutdown();
      output = errors.getOutput();
    }
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    verify(this.startup).shutdownNow();

    final boolean contained = output.contains("Interrupted while waiting for audio startup to stop");
    assertTrue(contained, output);
  }

  @Test
  void shouldStopOutputsThatFinishStartingAfterTheShutdown() throws InterruptedException {
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
  void shouldNotShutDownABotTwiceWhenItFailsAfterTheShutdown() {
    this.startInTheBackground();
    this.enableDiscord();
    when(this.jda.getGuildById(GUILD_ID)).thenReturn(null);
    this.provider.initialize();
    this.provider.shutdown();
    this.runDeferred();
    verify(this.jda, times(1)).shutdown();
    verify(this.jda, never()).shutdownNow();

    final boolean ready = this.provider.isDiscordBotReady();
    assertFalse(ready);
  }
}
