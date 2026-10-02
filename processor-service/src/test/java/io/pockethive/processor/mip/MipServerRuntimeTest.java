package io.pockethive.processor.mip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.handler.Iso8583WireProfile;
import io.pockethive.work.api.IsoSchemaRef;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MipServerRuntimeTest {
  @Test void unusableGuidesFailBeforeBinding() throws Exception {
    var worker = configuredWorker();
    var settings = new HashMap<>((Map<String, Object>) worker.privateConfig().get(MipServerConfigResolver.SETTINGS_KEY));
    var root = java.nio.file.Files.createTempDirectory("mip-invalid-guides");
    var pack = java.nio.file.Files.createDirectories(root.resolve("synthetic/1"));
    java.nio.file.Files.writeString(pack.resolve("synthetic-j8583.xml"), """
        <j8583-config>
          <parse type="0100"><field num="3" type="NUMERIC" length="6"/></parse>
          <parse type="0110" extends="0100"/>
          <parse type="0800" extends="0100"/>
          <parse type="0810" extends="0100"/>
        </j8583-config>
        """);
    var schema = new HashMap<>((Map<String, Object>) settings.get("schemaRef"));
    schema.put("schemaRegistryRoot", root.toString());
    settings.put("schemaRef", schema);
    var unusable = MipServerConfigResolverTest.worker(worker.baseUrl(), settings);
    try (var runtime = new MipServerRuntime(new ObjectMapper(), (config, pipeline) -> {
      throw new AssertionError("An unusable pack must fail before binding");
    })) {
      assertThatThrownBy(() -> runtime.configure(unusable)).isInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Undefined ISO field 11 for MTI 0100");
    }
  }

  @Test void malformedUpdatedUriRetiresOldPeerWithoutWork() throws Exception {
    var worker = configuredWorker();
    var channel = new AtomicReference<EmbeddedChannel>();
    try (var runtime = new MipServerRuntime(new ObjectMapper(), (config, pipeline) -> {
      channel.set(new EmbeddedChannel(pipeline));
      return () -> channel.get().close();
    })) {
      runtime.configure(worker);
      var invalid = MipServerConfigResolverTest.worker("mip://not a valid host:6036",
          (Map<String, Object>) worker.privateConfig().get(MipServerConfigResolver.SETTINGS_KEY));
      assertThatThrownBy(() -> runtime.configure(invalid)).isInstanceOf(IllegalStateException.class);
      assertThat(channel.get().isActive()).isFalse();
    } finally { if (channel.get() != null) channel.get().finishAndReleaseAll(); }
  }

  @Test void settingChangeRetiresListenerAndPreventsOldPeerEchoWithoutWork() throws Exception {
    var channel = new AtomicReference<EmbeddedChannel>();
    var worker = configuredWorker();
    try (var runtime = new MipServerRuntime(new ObjectMapper(), (config, pipeline) -> {
      channel.set(new EmbeddedChannel(pipeline));
      return () -> channel.get().close();
    })) {
      runtime.configure(worker);
      assertThat(channel.get().isActive()).isTrue();
      runtime.configure(worker);
      assertThat(channel.get().isActive()).isTrue();
      var changed = new HashMap<>(worker.privateConfig());
      var settings = new HashMap<>((Map<String, Object>) changed.get(MipServerConfigResolver.SETTINGS_KEY));
      settings.put("maxPending", 1);
      var changedWorker = MipServerConfigResolverTest.worker(worker.baseUrl(), settings);
      runtime.configure(changedWorker);
      assertThat(channel.get().isActive()).isFalse();
      assertThatThrownBy(() -> runtime.exchange(new byte[0], worker)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("restart");
      runtime.configure(worker);
      assertThat(channel.get().isActive()).isFalse();
    } finally { if (channel.get() != null) channel.get().finishAndReleaseAll(); }
  }

  @Test void missingOrChangedConfigurationFailsClosedAndStartupFailureIsExplicit() throws Exception {
    var worker = configuredWorker();
    var channel = new AtomicReference<EmbeddedChannel>();
    try (var runtime = new MipServerRuntime(new ObjectMapper(), (config, pipeline) -> {
      channel.set(new EmbeddedChannel(pipeline));
      return () -> channel.get().close();
    })) {
      runtime.configure(worker);
      runtime.configure(MipServerConfigResolverTest.worker(worker.baseUrl(), Map.of()));
      assertThat(channel.get().isActive()).isFalse();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageContaining("initialize");
    } finally { if (channel.get() != null) channel.get().finishAndReleaseAll(); }
    try (var runtime = new MipServerRuntime(new ObjectMapper(), (config, pipeline) -> {
      throw new java.io.IOException("synthetic binding failure");
    })) {
      assertThatThrownBy(() -> runtime.configure(worker)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> runtime.exchange(new byte[0], worker)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test void echoesOnListenerBeforeFirstWorkAndConfigurationRemovalClosesPeer() throws Exception {
    var worker = configuredWorker();
    var channel = new AtomicReference<EmbeddedChannel>();
    try (var runtime = new MipServerRuntime(new ObjectMapper(), (config, pipeline) -> {
      channel.set(new EmbeddedChannel(pipeline));
      return () -> channel.get().close();
    })) {
      runtime.configure(worker);
      var schema = new ObjectMapper().convertValue(((Map<?, ?>) worker.privateConfig()
          .get(MipServerConfigResolver.SETTINGS_KEY)).get("schemaRef"), IsoSchemaRef.class);
      byte[] echo = new Iso8583Codec().encode(0x0800, Map.of(7, "1002123045", 11, "000001", 70, "270"), schema);
      channel.get().writeInbound(Unpooled.wrappedBuffer(Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP.frame(echo)));
      io.netty.buffer.ByteBuf reply = channel.get().readOutbound();
      assertThat(reply).isNotNull();
      reply.release();
      runtime.configurationRemoved();
      assertThat(channel.get().isActive()).isFalse();
    } finally { if (channel.get() != null) channel.get().finishAndReleaseAll(); }
  }

  static ProcessorWorkerConfig configuredWorker() throws Exception {
    var settings = new HashMap<>(MipServerConfigResolverTest.settings());
    var schema = new HashMap<>((Map<String, Object>) settings.get("schemaRef"));
    schema.put("schemaRegistryRoot", Path.of(MipServerRuntimeTest.class.getResource("/iso8583/schema-registry").toURI()).toString());
    settings.put("schemaRef", schema);
    return MipServerConfigResolverTest.worker("mip://127.0.0.1:6036", settings);
  }
}
