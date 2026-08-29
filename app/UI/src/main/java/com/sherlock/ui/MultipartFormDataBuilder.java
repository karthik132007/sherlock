package com.sherlock.ui;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MultipartFormDataBuilder {
    private final String boundary = "----SherlockBoundary" + System.currentTimeMillis();
    private final List<byte[]> parts = new ArrayList<>();

    public void addFile(String fieldName, File file) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";

        byte[] fileBytes = Files.readAllBytes(file.toPath());
        parts.add(header.getBytes(StandardCharsets.UTF_8));
        parts.add(fileBytes);
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public byte[] build() throws IOException {
        int totalSize = 0;
        for (byte[] part : parts) {
            totalSize += part.length;
        }
        totalSize += ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8).length;

        byte[] result = new byte[totalSize];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        byte[] closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(closing, 0, result, offset, closing.length);
        return result;
    }
}
