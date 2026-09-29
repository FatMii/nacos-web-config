package io.github.fatmii.nacoswebconfig.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.SourceError;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NacosConfigSourceTest {
    private static final ConfigRef UI = new ConfigRef("WEB_DEMO", "web-demo.public.json");

    @Test
    void managedSourceCreatesAndOwnsNamespaceBoundService() throws Exception {
        var service = mock(ConfigService.class);
        var properties = new Properties();
        properties.setProperty("serverAddr", "127.0.0.1:8848");
        properties.setProperty("namespace", "tenant-a");

        var source = NacosConfigSource.managed(
                properties,
                Duration.ofSeconds(3),
                supplied -> {
                    assertEquals(properties, supplied);
                    return service;
                });

        source.close();
        verify(service).shutDown();
    }

    @Test
    void borrowedServiceEmitsInitialValueAndRemovesOnlyItsListener() throws Exception {
        var service = mock(ConfigService.class);
        when(service.getConfigAndSignListener(eq(UI.dataId()), eq(UI.group()), eq(3_000L), any()))
                .thenReturn("{\"theme\":\"dark\"}");
        var events = new LinkedBlockingQueue<SourceEvent>();
        var source = NacosConfigSource.borrowed(service, Duration.ofSeconds(3));

        var watch = source.watch(Set.of(UI), events::add);

        var initial = assertInstanceOf(SourceEvent.Value.class, events.take());
        assertEquals(UI, initial.ref());
        assertEquals("{\"theme\":\"dark\"}", initial.content());

        var listener = ArgumentCaptor.forClass(Listener.class);
        verify(service).getConfigAndSignListener(
                eq(UI.dataId()), eq(UI.group()), eq(3_000L), listener.capture());
        watch.close();
        watch.close();
        source.close();

        verify(service).removeListener(UI.dataId(), UI.group(), listener.getValue());
        verify(service, never()).shutDown();
    }

    @Test
    void initialValueAlwaysPrecedesCallbackThatRacesWithRegistration() throws Exception {
        var service = mock(ConfigService.class);
        var events = new LinkedBlockingQueue<SourceEvent>();
        when(service.getConfigAndSignListener(eq(UI.dataId()), eq(UI.group()), eq(3_000L), any()))
                .thenAnswer(invocation -> {
                    var listener = invocation.getArgument(3, Listener.class);
                    listener.receiveConfigInfo("{\"version\":2}");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (events.isEmpty() && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    return "{\"version\":1}";
                });
        var source = NacosConfigSource.borrowed(service, Duration.ofSeconds(3));

        source.watch(Set.of(UI), events::add);

        assertEquals("{\"version\":1}", assertInstanceOf(SourceEvent.Value.class, events.take()).content());
        assertEquals("{\"version\":2}", assertInstanceOf(SourceEvent.Value.class, events.take()).content());
        source.close();
    }

    @Test
    void nullInitialAndCallbackAreDeletionsWhileLaterContentIsAValue() throws Exception {
        var service = mock(ConfigService.class);
        when(service.getConfigAndSignListener(eq(UI.dataId()), eq(UI.group()), eq(3_000L), any()))
                .thenReturn(null);
        var events = new LinkedBlockingQueue<SourceEvent>();
        var source = NacosConfigSource.borrowed(service, Duration.ofSeconds(3));

        source.watch(Set.of(UI), events::add);
        assertInstanceOf(SourceEvent.Deleted.class, events.take());

        var listener = ArgumentCaptor.forClass(Listener.class);
        verify(service).getConfigAndSignListener(
                eq(UI.dataId()), eq(UI.group()), eq(3_000L), listener.capture());
        listener.getValue().receiveConfigInfo(null);
        listener.getValue().receiveConfigInfo("{\"restored\":true}");

        assertInstanceOf(SourceEvent.Deleted.class, events.poll(2, TimeUnit.SECONDS));
        var restored = assertInstanceOf(SourceEvent.Value.class, events.poll(2, TimeUnit.SECONDS));
        assertEquals("{\"restored\":true}", restored.content());
        source.close();
    }

    @Test
    void ownedSourceClosesActiveWatchesAndServiceExactlyOnce() throws Exception {
        var service = mock(ConfigService.class);
        when(service.getConfigAndSignListener(any(), any(), eq(3_000L), any())).thenReturn(null);
        var flags = new ConfigRef("WEB_DEMO", "web-demo.flags.json");
        var source = NacosConfigSource.owned(service, Duration.ofSeconds(3));

        source.watch(Set.of(UI, flags), ignored -> {});
        source.close();
        source.close();

        verify(service).removeListener(eq(UI.dataId()), eq(UI.group()), any());
        verify(service).removeListener(eq(flags.dataId()), eq(flags.group()), any());
        verify(service, times(1)).shutDown();
    }

    @Test
    void initialRegistrationFailureReportsUnavailableAndRetries() throws Exception {
        var service = mock(ConfigService.class);
        when(service.getConfigAndSignListener(eq(UI.dataId()), eq(UI.group()), eq(3_000L), any()))
                .thenThrow(new NacosException(500, "temporarily unavailable"))
                .thenReturn("{\"recovered\":true}");
        when(service.getServerStatus()).thenReturn("UP");
        var events = new LinkedBlockingQueue<SourceEvent>();
        var source = NacosConfigSource.borrowed(
                service, Duration.ofSeconds(3), Duration.ofMillis(20));

        source.watch(Set.of(UI), events::add);

        var unavailable = assertInstanceOf(
                SourceEvent.Unavailable.class, events.poll(2, TimeUnit.SECONDS));
        assertEquals(SourceError.UNAVAILABLE, unavailable.error());
        var recovered = assertInstanceOf(SourceEvent.Value.class, events.poll(2, TimeUnit.SECONDS));
        assertEquals("{\"recovered\":true}", recovered.content());
        source.close();
        verify(service, times(1)).removeListener(eq(UI.dataId()), eq(UI.group()), any());
        verify(service, never()).shutDown();
    }

    @Test
    void callbacksAfterWatchCloseAreIgnored() throws Exception {
        var service = mock(ConfigService.class);
        when(service.getConfigAndSignListener(eq(UI.dataId()), eq(UI.group()), eq(3_000L), any()))
                .thenReturn(null);
        var events = new LinkedBlockingQueue<SourceEvent>();
        var source = NacosConfigSource.borrowed(service, Duration.ofSeconds(3));
        var watch = source.watch(Set.of(UI), events::add);
        events.take();
        var listener = ArgumentCaptor.forClass(Listener.class);
        verify(service).getConfigAndSignListener(
                eq(UI.dataId()), eq(UI.group()), eq(3_000L), listener.capture());

        watch.close();
        listener.getValue().receiveConfigInfo("{\"late\":true}");

        assertNull(events.poll(200, TimeUnit.MILLISECONDS));
        source.close();
    }

    @Test
    void unavailableSourceIsReportedOnceAndRecoveryReconfirmsCurrentValue() throws Exception {
        var service = mock(ConfigService.class);
        when(service.getConfigAndSignListener(eq(UI.dataId()), eq(UI.group()), eq(3_000L), any()))
                .thenReturn("{\"version\":1}");
        when(service.getServerStatus()).thenReturn("DOWN", "DOWN", "UP", "UP");
        when(service.getConfig(UI.dataId(), UI.group(), 3_000L)).thenReturn("{\"version\":2}");
        var events = new LinkedBlockingQueue<SourceEvent>();
        var source = NacosConfigSource.borrowed(
                service, Duration.ofSeconds(3), Duration.ofMillis(20));

        source.watch(Set.of(UI), events::add);

        assertEquals("{\"version\":1}", assertInstanceOf(SourceEvent.Value.class, events.poll(2, TimeUnit.SECONDS)).content());
        assertInstanceOf(SourceEvent.Unavailable.class, events.poll(2, TimeUnit.SECONDS));
        assertEquals("{\"version\":2}", assertInstanceOf(SourceEvent.Value.class, events.poll(2, TimeUnit.SECONDS)).content());
        assertNull(events.poll(100, TimeUnit.MILLISECONDS));
        source.close();
    }
}
