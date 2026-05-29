package com.example.homeassistantvoice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class WakeModel {
    static final class Result {
        final boolean matched;
        final double confidence;

        Result(boolean matched, double confidence) {
            this.matched = matched;
            this.confidence = confidence;
        }
    }

    private static final int FRAME_SIZE = 400;
    private static final int HOP_SIZE = 160;
    private static final double[] PROBE_FREQS = {220.0, 350.0, 550.0, 800.0, 1200.0, 1800.0, 2600.0, 3800.0};

    private final int frameCount;
    private final int featureCount;
    private final double threshold;
    private final List<double[][]> templates;

    private WakeModel(int frameCount, int featureCount, double threshold, List<double[][]> templates) {
        this.frameCount = frameCount;
        this.featureCount = featureCount;
        this.threshold = threshold;
        this.templates = templates;
    }

    static WakeModel load(Context context) {
        try (InputStream in = context.getAssets().open("wake_model.json")) {
            byte[] bytes = new byte[in.available()];
            int read = in.read(bytes);
            JSONObject json = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
            int frameCount = json.optInt("frame_count", 48);
            int featureCount = json.optInt("feature_count", 10);
            double threshold = json.optDouble("threshold", 0.62);
            JSONArray array = json.getJSONArray("templates");
            List<double[][]> templates = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONArray rows = array.getJSONObject(i).getJSONArray("features");
                double[][] features = new double[rows.length()][featureCount];
                for (int row = 0; row < rows.length(); row++) {
                    JSONArray values = rows.getJSONArray(row);
                    for (int col = 0; col < featureCount; col++) {
                        features[row][col] = values.getDouble(col);
                    }
                }
                templates.add(features);
            }
            return new WakeModel(frameCount, featureCount, threshold, templates);
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean isLoaded() {
        return !templates.isEmpty();
    }

    Result match(short[] pcm, int samples) {
        if (templates.isEmpty() || samples <= 0) {
            return new Result(false, 0.0);
        }
        double[][] features = extractFeatures(pcm, samples);
        double bestDistance = Double.MAX_VALUE;
        for (double[][] template : templates) {
            bestDistance = Math.min(bestDistance, distance(features, template));
        }
        double confidence = 1.0 / (1.0 + bestDistance);
        return new Result(confidence >= threshold, confidence);
    }

    private double[][] extractFeatures(short[] pcm, int samples) {
        double[] normalized = trimSilence(pcm, samples);
        double[][] frames = frameFeatures(normalized);
        return normalize(resample(frames, frameCount));
    }

    private double[] trimSilence(short[] pcm, int samples) {
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++) {
            values[i] = pcm[i] / 32768.0;
        }
        if (samples < HOP_SIZE) {
            return values;
        }
        int frameCount = Math.max(1, (samples - HOP_SIZE) / HOP_SIZE);
        double[] levels = new double[frameCount];
        double peak = 0.0;
        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * HOP_SIZE;
            double sum = 0.0;
            for (int i = 0; i < HOP_SIZE && start + i < samples; i++) {
                sum += values[start + i] * values[start + i];
            }
            levels[frame] = Math.sqrt(sum / HOP_SIZE);
            peak = Math.max(peak, levels[frame]);
        }
        double threshold = Math.max(0.004, peak * 0.18);
        int first = 0;
        int last = samples;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] >= threshold) {
                first = Math.max(0, i * HOP_SIZE - HOP_SIZE * 2);
                break;
            }
        }
        for (int i = levels.length - 1; i >= 0; i--) {
            if (levels[i] >= threshold) {
                last = Math.min(samples, (i + 3) * HOP_SIZE);
                break;
            }
        }
        if (last - first < WavRecorder.SAMPLE_RATE * 0.45) {
            return values;
        }
        double[] trimmed = new double[last - first];
        System.arraycopy(values, first, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private double[][] frameFeatures(double[] samples) {
        if (samples.length < FRAME_SIZE) {
            double[] padded = new double[FRAME_SIZE];
            System.arraycopy(samples, 0, padded, 0, samples.length);
            samples = padded;
        }
        int rows = Math.max(1, ((samples.length - FRAME_SIZE) / HOP_SIZE) + 1);
        double[][] output = new double[rows][featureCount];
        for (int row = 0; row < rows; row++) {
            int start = row * HOP_SIZE;
            double[] frame = new double[FRAME_SIZE];
            System.arraycopy(samples, start, frame, 0, Math.min(FRAME_SIZE, samples.length - start));
            double sum = 0.0;
            int zcr = 0;
            for (int i = 0; i < FRAME_SIZE; i++) {
                sum += frame[i] * frame[i];
                if (i > 0 && (frame[i - 1] >= 0.0) != (frame[i] >= 0.0)) {
                    zcr++;
                }
            }
            output[row][0] = Math.log(Math.max(Math.sqrt(sum / FRAME_SIZE), 0.00001));
            output[row][1] = zcr / (double) FRAME_SIZE;
            for (int i = 0; i < PROBE_FREQS.length; i++) {
                output[row][i + 2] = Math.log(goertzelPower(frame, PROBE_FREQS[i]) + 0.00000001);
            }
        }
        return output;
    }

    private double goertzelPower(double[] frame, double freq) {
        double omega = 2.0 * Math.PI * freq / WavRecorder.SAMPLE_RATE;
        double coeff = 2.0 * Math.cos(omega);
        double prev = 0.0;
        double prev2 = 0.0;
        for (double sample : frame) {
            double value = sample + coeff * prev - prev2;
            prev2 = prev;
            prev = value;
        }
        return prev2 * prev2 + prev * prev - coeff * prev * prev2;
    }

    private double[][] resample(double[][] input, int targetRows) {
        if (input.length == targetRows) {
            return input;
        }
        double[][] output = new double[targetRows][featureCount];
        for (int row = 0; row < targetRows; row++) {
            double pos = row * (input.length - 1.0) / Math.max(1.0, targetRows - 1.0);
            int left = (int) Math.floor(pos);
            int right = Math.min(input.length - 1, left + 1);
            double frac = pos - left;
            for (int col = 0; col < featureCount; col++) {
                output[row][col] = input[left][col] * (1.0 - frac) + input[right][col] * frac;
            }
        }
        return output;
    }

    private double[][] normalize(double[][] input) {
        double[] means = new double[featureCount];
        double[] stds = new double[featureCount];
        for (double[] row : input) {
            for (int col = 0; col < featureCount; col++) {
                means[col] += row[col];
            }
        }
        for (int col = 0; col < featureCount; col++) {
            means[col] /= input.length;
        }
        for (double[] row : input) {
            for (int col = 0; col < featureCount; col++) {
                double delta = row[col] - means[col];
                stds[col] += delta * delta;
            }
        }
        for (int col = 0; col < featureCount; col++) {
            stds[col] = Math.max(0.001, Math.sqrt(stds[col] / input.length));
        }
        double[][] output = new double[input.length][featureCount];
        for (int row = 0; row < input.length; row++) {
            for (int col = 0; col < featureCount; col++) {
                output[row][col] = (input[row][col] - means[col]) / stds[col];
            }
        }
        return output;
    }

    private double distance(double[][] a, double[][] b) {
        int rows = Math.min(a.length, b.length);
        int cols = Math.min(a[0].length, b[0].length);
        double total = 0.0;
        int count = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                total += Math.abs(a[row][col] - b[row][col]);
                count++;
            }
        }
        return total / Math.max(1, count);
    }
}
