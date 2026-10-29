package com.atakmap.android.lingualinkledger.plugin;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the test set's PNG images into ARGB pixels. Unit tests compile
 * against Android's class library, which has no java.awt or
 * javax.imageio, so this small reader stands in. It handles what the
 * test set uses, 8-bit greyscale, RGB or RGBA, not interlaced, and fails
 * loudly on anything else.
 */
final class TestPng {

    final int width;
    final int height;
    final int[] argb;

    private TestPng(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    static TestPng read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            if (!Arrays.equals(sig, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
                throw new IOException(file + ": not a PNG");
            int w = 0, h = 0, colourType = -1;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            while (true) {
                int length = in.readInt();
                byte[] type = new byte[4];
                in.readFully(type);
                byte[] data = new byte[length];
                in.readFully(data);
                in.readInt(); // CRC
                String t = new String(type, "US-ASCII");
                if (t.equals("IHDR")) {
                    w = int32(data, 0);
                    h = int32(data, 4);
                    int depth = data[8], interlace = data[12];
                    colourType = data[9];
                    if (depth != 8 || interlace != 0 || (colourType != 0 && colourType != 2 && colourType != 6))
                        throw new IOException(file + ": unsupported PNG (depth " + depth + ", colour type "
                                + colourType + ", interlace " + interlace + ")");
                } else if (t.equals("IDAT")) {
                    idat.write(data);
                } else if (t.equals("IEND")) {
                    break;
                }
            }
            int channels = colourType == 0 ? 1 : colourType == 2 ? 3 : 4;
            byte[] raw = inflate(idat.toByteArray(), (w * channels + 1) * h);
            return new TestPng(w, h, unfilter(raw, w, h, channels));
        }
    }

    private static int[] unfilter(byte[] raw, int w, int h, int ch) throws IOException {
        int stride = w * ch;
        byte[] prev = new byte[stride], cur = new byte[stride];
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int filter = raw[y * (stride + 1)] & 0xFF;
            System.arraycopy(raw, y * (stride + 1) + 1, cur, 0, stride);
            for (int i = 0; i < stride; i++) {
                int a = i >= ch ? cur[i - ch] & 0xFF : 0, b = prev[i] & 0xFF;
                int c = i >= ch ? prev[i - ch] & 0xFF : 0, x = cur[i] & 0xFF;
                switch (filter) {
                    case 0: break;
                    case 1: x += a; break;
                    case 2: x += b; break;
                    case 3: x += (a + b) >> 1; break;
                    case 4: x += paeth(a, b, c); break;
                    default: throw new IOException("PNG filter " + filter);
                }
                cur[i] = (byte) x;
            }
            for (int px = 0; px < w; px++) {
                int o = px * ch;
                int r = cur[o] & 0xFF;
                int g = ch >= 3 ? cur[o + 1] & 0xFF : r, bl = ch >= 3 ? cur[o + 2] & 0xFF : r;
                int al = ch == 4 ? cur[o + 3] & 0xFF : 255;
                out[y * w + px] = (al << 24) | (r << 16) | (g << 8) | bl;
            }
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return out;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static byte[] inflate(byte[] data, int size) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        byte[] out = new byte[size];
        try {
            int n = 0;
            while (n < size && !inf.finished()) {
                int got = inf.inflate(out, n, size - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary()))
                    break;
                n += got;
            }
            if (n != size)
                throw new IOException("PNG data: " + n + " bytes, expected " + size);
        } catch (DataFormatException e) {
            throw new IOException("PNG data: " + e.getMessage());
        } finally {
            inf.end();
        }
        return out;
    }

    private static int int32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** A smaller copy, each new pixel the average of the source pixels it covers. */
    TestPng scaledToWidth(int newWidth) {
        int newHeight = Math.round(height * (newWidth / (float) width));
        int[] out = new int[newWidth * newHeight];
        for (int y = 0; y < newHeight; y++) {
            int y0 = y * height / newHeight, y1 = Math.max(y0 + 1, (y + 1) * height / newHeight);
            for (int x = 0; x < newWidth; x++) {
                int x0 = x * width / newWidth, x1 = Math.max(x0 + 1, (x + 1) * width / newWidth);
                long r = 0, g = 0, b = 0, n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = argb[sy * width + sx];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                out[y * newWidth + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return new TestPng(newWidth, newHeight, out);
    }

    /** A white image, holding no code. */
    static TestPng blank(int width, int height) {
        int[] white = new int[width * height];
        Arrays.fill(white, 0xFFFFFFFF);
        return new TestPng(width, height, white);
    }
}
