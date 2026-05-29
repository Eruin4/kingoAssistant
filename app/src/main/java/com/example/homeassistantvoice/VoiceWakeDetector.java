package com.example.homeassistantvoice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

final class VoiceWakeDetector {
    interface Callback {
        void onWakeDetected(double levelDb);

        void onWakeRejected(double levelDb, double confidence);

        void onWakeError(String message);
    }

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final double DEFAULT_THRESHOLD_DB = -38.0;
    private static final int REQUIRED_HOT_FRAMES = 2;
    private static final long COOLDOWN_MS = 2500;
    private static final int MATCH_BUFFER_SAMPLES = (int) (WavRecorder.SAMPLE_RATE * 1.8);

    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final WakeModel wakeModel;
    private Thread worker;
    private AudioRecord audioRecord;
    private double thresholdDb = DEFAULT_THRESHOLD_DB;

    VoiceWakeDetector(WakeModel wakeModel) {
        this.wakeModel = wakeModel;
    }

    static double measureAverageDb(long durationMs) throws java.io.IOException {
        int minBuffer = AudioRecord.getMinBufferSize(WavRecorder.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer <= 0) {
            throw new java.io.IOException("AudioRecord buffer init failed");
        }
        int bufferSize = Math.max(minBuffer, WavRecorder.SAMPLE_RATE);
        short[] buffer = new short[bufferSize / 2];
        AudioRecord record = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavRecorder.SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new java.io.IOException("AudioRecord init failed");
        }
        long endAt = System.currentTimeMillis() + Math.max(250, durationMs);
        double sum = 0.0;
        int count = 0;
        try {
            record.startRecording();
            while (System.currentTimeMillis() < endAt) {
                int read = record.read(buffer, 0, buffer.length);
                if (read > 0) {
                    sum += calculateDb(buffer, read);
                    count++;
                }
            }
        } finally {
            record.stop();
            record.release();
        }
        if (count == 0) {
            throw new java.io.IOException("No calibration audio read");
        }
        return sum / count;
    }

    boolean isListening() {
        return listening.get();
    }

    void setThresholdDb(double thresholdDb) {
        this.thresholdDb = thresholdDb;
    }

    void start(Callback callback) {
        if (!listening.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> listen(callback), "voice-wake-detector");
        worker.start();
    }

    void stop() {
        if (!listening.compareAndSet(true, false)) {
            return;
        }
        if (audioRecord != null) {
            audioRecord.stop();
        }
        if (worker != null) {
            try {
                worker.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseRecorder();
    }

    private void listen(Callback callback) {
        int minBuffer = AudioRecord.getMinBufferSize(WavRecorder.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer <= 0) {
            callback.onWakeError("Wake detector buffer init failed");
            listening.set(false);
            return;
        }

        int bufferSize = Math.max(minBuffer, WavRecorder.SAMPLE_RATE);
        short[] buffer = new short[bufferSize / 2];
        short[] matchBuffer = new short[MATCH_BUFFER_SAMPLES];
        int matchSamples = 0;
        int hotFrames = 0;
        long lastWakeAt = 0;
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WavRecorder.SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                callback.onWakeError("Wake detector AudioRecord init failed");
                listening.set(false);
                return;
            }

            audioRecord.startRecording();
            while (listening.get()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                appendSamples(matchBuffer, matchSamples, buffer, read);
                matchSamples = Math.min(matchBuffer.length, matchSamples + read);
                double levelDb = calculateDb(buffer, read);
                if (levelDb >= thresholdDb) {
                    hotFrames++;
                } else {
                    hotFrames = Math.max(0, hotFrames - 1);
                }

                long now = System.currentTimeMillis();
                if (hotFrames >= REQUIRED_HOT_FRAMES && now - lastWakeAt >= COOLDOWN_MS) {
                    lastWakeAt = now;
                    hotFrames = 0;
                    WakeModel.Result result = matchWake(matchBuffer, matchSamples);
                    if (result.matched) {
                        callback.onWakeDetected(levelDb);
                    } else {
                        callback.onWakeRejected(levelDb, result.confidence);
                    }
                }
            }
        } catch (Exception e) {
            if (listening.get()) {
                callback.onWakeError(e.getMessage());
            }
        } finally {
            releaseRecorder();
            listening.set(false);
        }
    }

    private WakeModel.Result matchWake(short[] matchBuffer, int matchSamples) {
        if (wakeModel == null || !wakeModel.isLoaded()) {
            return new WakeModel.Result(true, 1.0);
        }
        int samples = Math.min(matchSamples, matchBuffer.length);
        short[] ordered = Arrays.copyOfRange(matchBuffer, matchBuffer.length - samples, matchBuffer.length);
        return wakeModel.match(ordered, samples);
    }

    private void appendSamples(short[] target, int targetSamples, short[] source, int sourceSamples) {
        int copy = Math.min(sourceSamples, target.length);
        System.arraycopy(target, copy, target, 0, target.length - copy);
        System.arraycopy(source, sourceSamples - copy, target, target.length - copy, copy);
    }

    private void releaseRecorder() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    private static double calculateDb(short[] buffer, int samples) {
        double sum = 0.0;
        for (int i = 0; i < samples; i++) {
            double sample = buffer[i] / 32768.0;
            sum += sample * sample;
        }
        double rms = Math.sqrt(sum / samples);
        return 20.0 * Math.log10(Math.max(rms, 0.00001));
    }
}
