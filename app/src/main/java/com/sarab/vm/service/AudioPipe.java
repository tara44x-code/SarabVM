package com.sarab.vm.service;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.system.ErrnoException;
import android.system.Os;

import com.sarab.vm.core.Lang;
import com.sarab.vm.core.WavHeader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays the virtual machine's sound. QEMU writes its sound card output as a WAV stream into a
 * named pipe; one thread drains the pipe as fast as it can (so QEMU never waits on us) and a
 * second thread feeds Android's AudioTrack. If playback falls behind, old sound is dropped.
 */
final class AudioPipe {

    private final File pipe;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);
    private volatile boolean stopped;
    private volatile boolean opened;
    private volatile WavHeader format;

    AudioPipe(File pipe) {
        this.pipe = pipe;
    }

    String path() {
        return pipe.getAbsolutePath();
    }

    /** Creates the pipe and starts waiting for QEMU. Call before starting QEMU. */
    void start() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        pipe.delete();
        try {
            Os.mkfifo(pipe.getAbsolutePath(), 0600);
        } catch (ErrnoException e) {
            throw new IOException(Lang.t("تعذر تجهيز الصوت: ") + e.getMessage(), e);
        }
        Thread reader = new Thread(this::readLoop, "audio-read");
        reader.setDaemon(true);
        reader.start();
        Thread player = new Thread(this::playLoop, "audio-play");
        player.setDaemon(true);
        player.start();
    }

    private void readLoop() {
        try (InputStream in = new FileInputStream(pipe)) { // blocks until QEMU opens the pipe
            opened = true;
            byte[] header = new byte[44];
            int got = 0;
            while (got < header.length) {
                int n = in.read(header, got, header.length - got);
                if (n < 0) return;
                got += n;
            }
            format = WavHeader.parse(header);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (format == null || stopped) continue; // keep draining so QEMU never blocks
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                if (!queue.offer(chunk)) {
                    queue.poll();      // behind: drop the oldest piece
                    queue.offer(chunk);
                }
            }
        } catch (IOException ignored) {
            // machine stopped
        } finally {
            stopped = true;
            //noinspection ResultOfMethodCallIgnored
            pipe.delete();
        }
    }

    private void playLoop() {
        AudioTrack track = null;
        try {
            while (!stopped) {
                byte[] chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                if (track == null) {
                    WavHeader f = format;
                    if (f == null) continue;
                    track = createTrack(f);
                    if (track == null) return;
                    track.play();
                }
                track.write(chunk, 0, chunk.length);
            }
        } catch (InterruptedException | RuntimeException ignored) {
            // stop playing; the reader keeps draining
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                    // already stopped
                }
                track.release();
            }
        }
    }

    private static AudioTrack createTrack(WavHeader f) {
        int channelMask = f.channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = f.bits == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
        int min = AudioTrack.getMinBufferSize(f.sampleRate, channelMask, encoding);
        if (min <= 0) return null;
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(f.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(min * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** Called when the machine stops (or failed to start). */
    void stop() {
        stopped = true;
        if (!opened) {
            // the reader is still waiting for QEMU to open the pipe: open it ourselves to release it
            try (FileOutputStream unblock = new FileOutputStream(pipe)) {
                unblock.flush();
            } catch (IOException ignored) {
                // nothing waiting
            }
        }
        //noinspection ResultOfMethodCallIgnored
        pipe.delete();
    }
}
