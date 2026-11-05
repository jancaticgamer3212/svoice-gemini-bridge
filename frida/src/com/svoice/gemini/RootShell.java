package com.svoice.gemini;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Runs a single command through the su binary and collects its combined output. */
final class RootShell {
    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private static final long TIMEOUT_MS = 180000;

    private RootShell() {
    }

    static Result run(String command) throws IOException, InterruptedException {
        final Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("no root shell available (" + e.getMessage() + ")", e);
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.close();
        } catch (IOException ignored) {
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[16384];
                int n;
                try {
                    InputStream in = process.getInputStream();
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        Thread timer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(TIMEOUT_MS);
                    process.destroy();
                } catch (InterruptedException ignored) {
                }
            }
        });
        reader.setDaemon(true);
        timer.setDaemon(true);
        reader.start();
        timer.start();

        int code = process.waitFor();
        timer.interrupt();
        reader.join(2000);
        String text;
        try {
            text = out.toString("UTF-8");
        } catch (IOException e) {
            text = new String(out.toByteArray());
        }
        return new Result(code, text.trim());
    }
}
