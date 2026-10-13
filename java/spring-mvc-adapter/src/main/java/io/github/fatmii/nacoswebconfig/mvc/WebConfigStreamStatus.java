package io.github.fatmii.nacoswebconfig.mvc;

/**
 * Read-only view of the live SSE streams for operational probes.
 *
 * <p>Counters are process-local and count only frames the container accepted for writing; a frame
 * whose write failed is not counted. No method performs I/O or blocks.
 */
public interface WebConfigStreamStatus {
    /** @return streams currently open in this process */
    int activeConnections();

    /** @return snapshot frames written since start */
    long snapshotEvents();

    /** @return change frames written since start */
    long changeEvents();

    /** @return heartbeat frames written since start */
    long heartbeatEvents();
}
