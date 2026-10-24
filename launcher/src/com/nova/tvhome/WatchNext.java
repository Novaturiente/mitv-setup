package com.nova.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract.WatchNextPrograms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class WatchNext {
    static final class Program {
        String packageName, title, intentUri, posterUri, thumbnailUri;
        long lastEngagement;
        int positionMillis, durationMillis, watchNextType;
    }

    static List<Program> load(ContentResolver resolver, Set<String> allowedPackages) {
        String[] projection = {
                WatchNextPrograms.COLUMN_PACKAGE_NAME,
                WatchNextPrograms.COLUMN_TITLE,
                WatchNextPrograms.COLUMN_INTENT_URI,
                WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                WatchNextPrograms.COLUMN_DURATION_MILLIS,
                WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                WatchNextPrograms.COLUMN_BROWSABLE,
                WatchNextPrograms.COLUMN_POSTER_ART_URI,
                WatchNextPrograms.COLUMN_THUMBNAIL_URI
        };
        List<Program> candidates = new ArrayList<>();
        try (Cursor cursor = resolver.query(WatchNextPrograms.CONTENT_URI,
                projection, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    if (cursor.isNull(7) || cursor.getInt(7) != 1) continue;
                    Program program = new Program();
                    program.packageName = cursor.isNull(0) ? null : cursor.getString(0);
                    program.title = cursor.isNull(1) ? null : cursor.getString(1);
                    program.intentUri = cursor.isNull(2) ? null : cursor.getString(2);
                    program.lastEngagement = cursor.isNull(3) ? 0 : cursor.getLong(3);
                    program.positionMillis = cursor.isNull(4) ? 0 : cursor.getInt(4);
                    program.durationMillis = cursor.isNull(5) ? 0 : cursor.getInt(5);
                    program.watchNextType = cursor.isNull(6) ? -1 : cursor.getInt(6);
                    program.posterUri = cursor.isNull(8) ? null : cursor.getString(8);
                    program.thumbnailUri = cursor.isNull(9) ? null : cursor.getString(9);
                    candidates.add(program);
                }
            }
        }
        return select(candidates, allowedPackages, 5);
    }

    static List<Program> select(List<Program> candidates, Set<String> allowedPackages, int limit) {
        List<Program> selected = new ArrayList<>();
        if (limit <= 0) return selected;
        for (Program program : candidates) {
            if (program.packageName == null || !allowedPackages.contains(program.packageName)
                    || "org.smarttube.stable".equals(program.packageName)
                    || "com.amazon.amazonvideo.livingroom".equals(program.packageName)
                    || program.watchNextType != WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    || program.title == null || program.title.trim().isEmpty()
                    || program.intentUri == null || program.intentUri.trim().isEmpty()
                    || program.positionMillis < 0 || program.durationMillis < 0
                    || (program.durationMillis > 0 && program.positionMillis >= program.durationMillis)) {
                continue;
            }
            selected.add(program);
        }
        selected.sort(Comparator.comparingLong((Program program) -> program.lastEngagement).reversed());
        return new ArrayList<>(selected.subList(0, Math.min(limit, selected.size())));
    }
}
