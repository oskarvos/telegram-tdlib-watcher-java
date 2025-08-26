package com.oleg.td;

import java.io.IOException;
import java.io.OutputStream;

public class LogTeeOutputStream extends OutputStream {
    private final OutputStream original;
    private final StringBuilder lineBuf = new StringBuilder();

    public LogTeeOutputStream(OutputStream original) {
        this.original = original;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        original.write(b);
        if (b == '\n') {
            flushLine();
        } else {
            lineBuf.append((char) b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        original.write(b, off, len);
        for (int i = off; i < off + len; i++) {
            if (b[i] == '\n') flushLine();
            else lineBuf.append((char) b[i]);
        }
    }

    @Override
    public synchronized void flush() throws IOException { original.flush(); }

    @Override
    public synchronized void close() throws IOException { original.close(); }

    private void flushLine() {
        String s = lineBuf.toString();
        lineBuf.setLength(0);
        if (!s.isEmpty()) LogHub.get().publish(s);
    }
}
