package io.pockethive.processor.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.transport.TcpBehavior;
import io.pockethive.processor.transport.TcpException;
import io.pockethive.processor.transport.TcpRequest;
import io.pockethive.processor.transport.TcpResponse;
import io.pockethive.processor.transport.TcpTransportLease;
import io.pockethive.processor.transport.TcpTransportRuntime;
import io.pockethive.work.api.WorkerContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class Iso8583ClientExchangeTest {
  @Test
  void preservesClientFramingCredentialsAndBoundedRetriesAndReleasesLease() throws Exception {
    var transport = new TcpTransportConfig("socket", 100, 200, 8192, true, 1, true, true,
        TcpTransportConfig.ConnectionReuse.NONE, 1);
    var config = new ProcessorWorkerConfig("tcps://host.example:6036",
        ProcessorWorkerConfig.Mode.THREAD_COUNT, 1, null,
        ProcessorWorkerConfig.ConnectionReuse.NONE, false, 1000, true, transport);
    var runtime = mock(TcpTransportRuntime.class);
    var lease = mock(TcpTransportLease.class);
    when(runtime.acquire(transport)).thenReturn(lease);
    when(lease.config()).thenReturn(transport);
    byte[] payload = {0x30, 0x31, 0x30, 0x30, (byte) 0xA0};
    byte[] response = {0x30, 0x31, 0x31, 0x30, (byte) 0xB0};
    when(lease.execute(any(), eq(TcpBehavior.LENGTH_PREFIX_2B)))
        .thenThrow(new TcpException("first client write failed"))
        .thenReturn(new TcpResponse(200, response, 1));
    var context = mock(WorkerContext.class);
    when(context.logger()).thenReturn(mock(Logger.class));

    assertThat(new Iso8583ClientExchange(runtime).exchange(payload,
        Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP, Iso8583Endpoint.parse(config.baseUrl()),
        config, Map.of("keyStorePath", "/mounted/client.p12"), context)).containsExactly(response);

    var capture = ArgumentCaptor.forClass(TcpRequest.class);
    verify(lease, times(2)).execute(capture.capture(), eq(TcpBehavior.LENGTH_PREFIX_2B));
    assertThat(capture.getValue().payload()).containsExactly(0, 5, 0x30, 0x31, 0x30, 0x30, (byte) 0xA0);
    assertThat(capture.getValue().host()).isEqualTo("host.example");
    assertThat(capture.getValue().options())
        .containsEntry("ssl", true)
        .containsEntry("sslVerify", true)
        .containsEntry("connectTimeoutMs", 100)
        .containsEntry("readTimeoutMs", 200)
        .containsEntry("keyStorePath", "/mounted/client.p12");
    verify(lease).close();
  }

  @Test
  void failedClientExchangeReleasesLeaseWithoutUnconfiguredRetry() throws Exception {
    var transport = new TcpTransportConfig("socket", 100, 200, 8192, true, 1, true, false,
        TcpTransportConfig.ConnectionReuse.NONE, 0);
    var config = new ProcessorWorkerConfig("tcp://host.example:6036",
        ProcessorWorkerConfig.Mode.THREAD_COUNT, 1, null,
        ProcessorWorkerConfig.ConnectionReuse.NONE, false, 1000, false, transport);
    var runtime = mock(TcpTransportRuntime.class);
    var lease = mock(TcpTransportLease.class);
    when(runtime.acquire(transport)).thenReturn(lease);
    when(lease.config()).thenReturn(transport);
    when(lease.execute(any(), eq(TcpBehavior.LENGTH_PREFIX_2B))).thenThrow(new TcpException("failed"));
    assertThatThrownBy(() -> new Iso8583ClientExchange(runtime).exchange(new byte[]{1},
        Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP, Iso8583Endpoint.parse(config.baseUrl()),
        config, Map.of(), mock(WorkerContext.class))).isInstanceOf(TcpException.class);
    verify(lease).execute(any(), eq(TcpBehavior.LENGTH_PREFIX_2B));
    verify(lease).close();
  }
}
