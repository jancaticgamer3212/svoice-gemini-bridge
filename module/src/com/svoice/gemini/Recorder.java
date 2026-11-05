package com.svoice.gemini;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class Recorder {
    static final int SAMPLE_RATE = 16000;

    private static final int MAX_MS = 15000;
    private static final int NO_SPEECH_MS = 6000;
    private static final int END_SILENCE_MS = 1000;
    private static final int CHUNK_SAMPLES = 1600; // 100 ms
    private static final int PRE_ROLL_CHUNKS = 4;

    private Recorder() {}

    static final class Turn {
        volatile boolean cancelled;
    }

    static byte[] record(Turn turn) throws Exception {
        AudioRecord rec = open(turn);
        if (rec == null) throw new IllegalStateException("Microphone is busy. Close other voice apps and try again.");

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] buf = new short[CHUNK_SAMPLES];
        List<byte[]> preRoll = new ArrayList<byte[]>();
        boolean speech = false;
        int speechMs = 0;
        int silenceMs = 0;
        double noiseFloor = Double.MAX_VALUE;
        double threshold = 700.0;

        try {
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Could not start the microphone.");
            }
            long start = SystemClock.elapsedRealtime();
            int calibration = 0;

            while (!turn.cancelled) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break;
                    continue;
                }
                int elapsed = (int) (SystemClock.elapsedRealtime() - start);
                byte[] chunk = toLittleEndian(buf, n);
                double rms = rms(buf, n);

                if (calibration < 3) {
                    calibration++;
                    if (rms < noiseFloor) noiseFloor = rms;
                    preRoll.add(chunk);
                    if (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.remove(0);
                    if (calibration == 3) {
                        threshold = Math.max(600.0, Math.min(2000.0, noiseFloor * 2.5));
                        if (threshold < 600.0) threshold = 600.0;
                    }
                    continue;
                }

                if (!speech) {
                    preRoll.add(chunk);
                    if (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.remove(0);
                    if (rms > threshold) {
                        speech = true;
                        speechMs = 0;
                        silenceMs = 0;
                        for (byte[] c : preRoll) pcm.write(c, 0, c.length);
                        preRoll.clear();
                    } else if (elapsed > NO_SPEECH_MS) {
                        break;
                    }
                } else {
                    pcm.write(chunk, 0, chunk.length);
                    speechMs += 100;
                    if (rms > threshold) {
                        silenceMs = 0;
                    } else {
                        silenceMs += 100;
                        if (silenceMs >= END_SILENCE_MS) break;
                    }
                }

                if (elapsed >= MAX_MS) break;
            }
        } finally {
            try { rec.stop(); } catch (Throwable ignored) {}
            try { rec.release(); } catch (Throwable ignored) {}
        }

        if (!speech || speechMs < 250) return null;
        byte[] data = pcm.toByteArray();
        if (data.length < 3200) return null;
        return wav(data);
    }

    private static AudioRecord open(Turn turn) throws InterruptedException {
        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = SAMPLE_RATE;
        int bufferBytes = Math.max(min * 2, CHUNK_SAMPLES * 2 * 2);

        for (int attempt = 0; attempt < 4; attempt++) {
            for (int source : sources) {
                AudioRecord rec = null;
                try {
                    rec = new AudioRecord(source, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                    if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                        return rec;
                    }
                } catch (Throwable ignored) {
                }
                if (rec != null) {
                    try { rec.release(); } catch (Throwable ignored) {}
                }
            }
            if (turn.cancelled) return null;
            Thread.sleep(250);
        }
        return null;
    }

    private static double rms(short[] buf, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double s = buf[i];
            sum += s * s;
        }
        return Math.sqrt(sum / Math.max(1, n));
    }

    private static byte[] toLittleEndian(short[] buf, int n) {
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int s = buf[i];
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] wav(byte[] pcm) {
        int dataLen = pcm.length;
        byte[] out = new byte[44 + dataLen];
        writeAscii(out, 0, "RIFF");
        writeInt(out, 4, 36 + dataLen);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeInt(out, 16, 16);
        writeShort(out, 20, 1);
        writeShort(out, 22, 1);
        writeInt(out, 24, SAMPLE_RATE);
        writeInt(out, 28, SAMPLE_RATE * 2);
        writeShort(out, 32, 2);
        writeShort(out, 34, 16);
        writeAscii(out, 36, "data");
        writeInt(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeAscii(byte[] out, int offset, String s) {
        for (int i = 0; i < s.length(); i++) out[offset + i] = (byte) s.charAt(i);
    }

    private static void writeInt(byte[] out, int offset, int v) {
        out[offset] = (byte) (v & 0xFF);
        out[offset + 1] = (byte) ((v >> 8) & 0xFF);
        out[offset + 2] = (byte) ((v >> 16) & 0xFF);
        out[offset + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeShort(byte[] out, int offset, int v) {
        out[offset] = (byte) (v & 0xFF);
        out[offset + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
