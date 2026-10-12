package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class WebConfigErrorHandlerTest {
    @Test
    void treatsAsyncAndBrokenPipeAsBenignDisconnects() {
        assertTrue(BenignDisconnects.isBenign(new AsyncRequestNotUsableException("gone")));
        assertTrue(BenignDisconnects.isBenign(new IOException("Broken pipe")));
        assertTrue(BenignDisconnects.isBenign(new IOException("Connection reset by peer")));
        assertTrue(BenignDisconnects.isBenign(
                new IOException("An established connection was aborted by the software in your host machine")));
        assertTrue(BenignDisconnects.isBenign(new IOException("你的主机中的软件中止了一个已建立的连接。")));
        assertTrue(BenignDisconnects.isBenign(new IOException("wrap", new IOException("Broken pipe"))));
    }

    @Test
    void doesNotTreatUnrelatedIoFailuresAsBenign() {
        assertFalse(BenignDisconnects.isBenign(new IOException("disk full")));
        assertFalse(BenignDisconnects.isBenign(new IllegalStateException("boom")));
    }
}
