package com.fer.a53performance;

import android.net.Uri;

public final class StorageItem {
    public final long id;
    public final Uri uri;
    public final String name;
    public final String path;
    public final String mime;
    public final long size;
    public final long modified;

    public StorageItem(long id, Uri uri, String name, String path, String mime, long size, long modified) {
        this.id = id; this.uri = uri; this.name = name == null ? "(sin nombre)" : name;
        this.path = path == null ? "" : path; this.mime = mime == null ? "" : mime;
        this.size = Math.max(0, size); this.modified = modified;
    }

    public String stableKey() { return uri != null ? uri.toString() : path; }
    public boolean isImage() { return mime.startsWith("image/"); }
    public boolean isVideo() { return mime.startsWith("video/"); }
    public boolean isAudio() { return mime.startsWith("audio/"); }
    public boolean isLarge() { return size >= 20L * 1024L * 1024L; }
}
