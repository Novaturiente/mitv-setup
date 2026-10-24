package com.nova.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class WatchNextTest {
    private static final Set<String> DOCK = Set.of(
            "dock.app", "org.smarttube.stable", "com.amazon.amazonvideo.livingroom");

    private static WatchNext.Program program(String packageName, long engagement) {
        WatchNext.Program program = new WatchNext.Program();
        program.packageName = packageName;
        program.title = "Episode " + engagement;
        program.intentUri = "app://episode/" + engagement;
        program.lastEngagement = engagement;
        program.positionMillis = 20;
        program.durationMillis = 100;
        program.watchNextType = 0;
        return program;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        WatchNext.Program valid = program("dock.app", 1);
        WatchNext.Program missingPackage = program(null, 10);
        List<WatchNext.Program> packageFiltered = WatchNext.select(Arrays.asList(
                program("grid.app", 20), program("org.smarttube.stable", 30),
                program("com.amazon.amazonvideo.livingroom", 40), missingPackage, valid), DOCK, 5);
        check(packageFiltered.size() == 1 && packageFiltered.get(0) == valid,
                "Only eligible dock packages survive; SmartTube, Prime Video and missing packages never survive");

        List<WatchNext.Program> invalid = new ArrayList<>();
        for (int type : new int[] {-1, 1, 2, 3}) {
            WatchNext.Program item = program("dock.app", type);
            item.watchNextType = type;
            invalid.add(item);
        }
        for (String title : new String[] {null, "", " \t\n"}) {
            WatchNext.Program item = program("dock.app", 1);
            item.title = title;
            invalid.add(item);
        }
        for (String link : new String[] {null, "", " \t\n"}) {
            WatchNext.Program item = program("dock.app", 1);
            item.intentUri = link;
            invalid.add(item);
        }
        for (int[] progress : new int[][] {{100, 100}, {101, 100}, {-1, 100}, {0, -1}}) {
            WatchNext.Program item = program("dock.app", 1);
            item.positionMillis = progress[0];
            item.durationMillis = progress[1];
            invalid.add(item);
        }
        check(WatchNext.select(invalid, DOCK, 5).isEmpty(),
                "Reject non-continue, blank metadata, completed, and negative progress items");

        List<WatchNext.Program> shuffled = new ArrayList<>();
        for (long engagement : new long[] {3, 7, 1, 6, 2, 5, 4}) {
            shuffled.add(program("dock.app", engagement));
        }
        List<WatchNext.Program> recent = WatchNext.select(shuffled, DOCK, 5);
        check(recent.size() == 5, "Cap at five items");
        for (int i = 0; i < recent.size(); i++) {
            check(recent.get(i).lastEngagement == 7 - i, "Sort descending by engagement");
        }
        check(shuffled.get(0).lastEngagement == 3, "Do not reorder caller's list");
        check(WatchNext.select(shuffled, DOCK, 2).size() == 2, "Honor smaller limits");
        check(WatchNext.select(shuffled, DOCK, 0).isEmpty(), "Zero limit returns empty");
        check(WatchNext.select(shuffled, DOCK, -1).isEmpty(), "Negative limit returns empty");

        WatchNext.Program unknownDuration = program("dock.app", Long.MAX_VALUE);
        unknownDuration.durationMillis = 0;
        WatchNext.Program zeroProgress = program("dock.app", Long.MIN_VALUE);
        zeroProgress.durationMillis = 0;
        zeroProgress.positionMillis = 0;
        List<WatchNext.Program> unknown = WatchNext.select(
                Arrays.asList(zeroProgress, unknownDuration), DOCK, 5);
        check(unknown.size() == 2 && unknown.get(0) == unknownDuration,
                "Unknown duration permits zero or positive progress; timestamps do not overflow");
        check(WatchNext.select(Collections.emptyList(), DOCK, 5).isEmpty(), "Empty input stays empty");
        check(WatchNext.select(List.of(valid), Collections.emptySet(), 5).isEmpty(),
                "Empty dock excludes all programs");
        System.out.println("WatchNextTest: PASS");
    }
}
