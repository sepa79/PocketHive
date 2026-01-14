package io.pockethive.tcpmock.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RecordingMode {
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicLong recordedCount = new AtomicLong(0);

    public void startRecording() {
        recording.set(true);
        recordedCount.set(0); // Reset count when starting
    }

    public void stopRecording() {
        recording.set(false);
    }

    public boolean isRecording() {
        return recording.get();
    }

    public long getRecordedCount() {
        return recordedCount.get();
    }

    public void incrementRecordedCount() {
        recordedCount.incrementAndGet();
    }
}
