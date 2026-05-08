package com.example.homeassistantvoice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

final class WavRecorder {
    static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread worker;
    private AudioRecord audioRecord;

    boolean isRecording() {
        return recording.get();
    }

    void start(File outputFile) throws IOException {
        if (recording.get()) {
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE * 2);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("AudioRecord initialization failed");
        }
        recording.set(true);
        worker = new Thread(() -> writeRecording(outputFile, bufferSize), "wav-recorder");
        worker.start();
    }

    void stop() {
        if (!recording.compareAndSet(true, false)) {
            return;
        }
        if (audioRecord != null) {
            audioRecord.stop();
        }
        if (worker != null) {
            try {
                worker.join(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void writeRecording(File outputFile, int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        long pcmBytes = 0;
        try (RandomAccessFile wav = new RandomAccessFile(outputFile, "rw")) {
            wav.setLength(0);
            writeHeader(wav, 0);
            audioRecord.startRecording();
            while (recording.get()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    wav.write(buffer, 0, read);
                    pcmBytes += read;
                }
            }
            wav.seek(0);
            writeHeader(wav, pcmBytes);
        } catch (IOException ignored) {
            recording.set(false);
        }
    }

    private static void writeHeader(RandomAccessFile out, long pcmBytes) throws IOException {
        long totalDataLen = pcmBytes + 36;
        long byteRate = SAMPLE_RATE * 2L;
        out.writeBytes("RIFF");
        writeIntLE(out, totalDataLen);
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, 1);
        writeIntLE(out, SAMPLE_RATE);
        writeIntLE(out, byteRate);
        writeShortLE(out, 2);
        writeShortLE(out, 16);
        out.writeBytes("data");
        writeIntLE(out, pcmBytes);
    }

    private static void writeIntLE(RandomAccessFile out, long value) throws IOException {
        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }

    private static void writeShortLE(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }
}
