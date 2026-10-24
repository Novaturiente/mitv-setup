package com.nova.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

final class Artwork {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static Bitmap load(String value, int targetWidth, int targetHeight) throws IOException {
        URL url = new URL(value);
        if (!isAllowedScheme(url.getProtocol())) throw new IOException("Unsupported artwork scheme");
        byte[] encoded = readHttps(url);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight,
                targetWidth, targetHeight);
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (bitmap != null && (bitmap.getWidth() > 2048 || bitmap.getHeight() > 2048
                || (long) bitmap.getWidth() * bitmap.getHeight()
                > pixelLimit(targetWidth, targetHeight))) {
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    private static byte[] readHttps(URL url) throws IOException {
        for (int redirects = 0; ; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Artwork redirects must remain HTTPS");
            }
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects >= 3 || location == null) {
                        throw new IOException("Invalid or excessive artwork redirects");
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Artwork HTTP status " + status);
                }
                try (InputStream input = connection.getInputStream()) {
                    return readLimited(input, MAX_BYTES);
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    static boolean isAllowedScheme(String scheme) {
        return "https".equalsIgnoreCase(scheme);
    }

    static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image size");
        long limit = pixelLimit(targetWidth, targetHeight);
        int sample = 1;
        while (true) {
            long decodedWidth = ((long) width + sample - 1) / sample;
            long decodedHeight = ((long) height + sample - 1) / sample;
            if (decodedWidth <= 2048 && decodedHeight <= 2048
                    && decodedWidth * decodedHeight <= limit) return sample;
            sample *= 2;
        }
    }

    private static long pixelLimit(int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("Invalid target size");
        }
        long pixels = (long) targetWidth * targetHeight;
        return pixels > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : Math.max(262144L, pixels * 4);
    }

    static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative byte limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        while (output.size() < maxBytes) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Artwork request interrupted");
            int count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - output.size()));
            if (count == -1) return output.toByteArray();
            output.write(buffer, 0, count);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Artwork request interrupted");
        if (input.read() != -1) throw new IOException("Artwork exceeds encoded byte limit");
        return output.toByteArray();
    }
}
