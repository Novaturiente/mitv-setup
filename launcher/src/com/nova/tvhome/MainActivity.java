package com.nova.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Apple TV style home screen. One activity, framework views only, own D-pad focus model. */
public class MainActivity extends Activity {
    private static final float SCALE = 1.08f;
    private static final int FALLBACK_GLOW = 0xFF5A7BFF;

    private static final class App {
        String pkg, label;
        Intent launch;
        Drawable art, icon, mono;
        boolean banner;
        int glow;
    }

    private final List<App> dockApps = new ArrayList<>(), gridApps = new ArrayList<>();
    private final List<LinearLayout> dockTiles = new ArrayList<>(), gridTiles = new ArrayList<>();
    private final List<TextView> pills = new ArrayList<>();
    private final List<WatchNext.Program> continuePrograms = new ArrayList<>();
    private final List<LinearLayout> continueTiles = new ArrayList<>();
    private final ExecutorService watchNextExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> artworkCache = new LruCache<>(5);
    private LinearLayout continueSection;
    private int baseGridTop, watchNextLoadId;

    private FrameLayout root;
    private LinearLayout page;
    private Glass glass;
    private TextView clock, date, wifiPill;
    private GradientDrawable glowBg;
    private Typeface thin, light, regular;

    private int W, H, M, G, P, cols, tileW, tileH, labelH, tileTotalH, bandH, headerH, rowH, gridTop, pillH;
    // row -1 = top bar, 0 = dock, then optional Continue Watching and grid rows.
    // Kept across app launches: "remember last tile".
    private int row = 0, col = 0, pill = 0, lastDockCol = 0;
    private float pageTarget = 0;
    private int glowColor = FALLBACK_GLOW;
    private View focusedArt, focusedLabel;
    private boolean resumed, laidOut;

    private LinearLayout dropdown;
    private final List<TextView> dropItems = new ArrayList<>();
    private final List<Runnable> dropActions = new ArrayList<>();
    private int dropIdx;

    private ConnectivityManager.NetworkCallback netCallback;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { recreate(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        thin = font(200);
        light = font(300);
        regular = font(400);
        build();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        register(packageReceiver, pf);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                String s = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? ssid(c)
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Online";
                runOnUiThread(() -> wifiPill.setText(s));
            }
            @Override public void onLost(Network n) { runOnUiThread(() -> wifiPill.setText("Offline")); }
        };
        cm.registerDefaultNetworkCallback(netCallback);
        if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 21);
        }
    }

    @Override
    protected void onDestroy() {
        watchNextExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        unregisterReceiver(packageReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(timeReceiver, tf);
        updateClock();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(timeReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (laidOut) applyFocus(false);
        refreshWatchNext();
    }

    @Override
    protected void onPause() {
        resumed = false;
        closeDropdown();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the dock, like Apple TV.
        if (resumed) {
            closeDropdown();
            if (!dockApps.isEmpty()) { row = 0; col = lastDockCol; }
            applyFocus(true);
        }
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    private Typeface font(int weight) {
        Typeface t = null;
        try {
            t = new Typeface.Builder(getAssets(), "Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException ignored) { }
        return t != null ? t : Typeface.create("sans-serif-light", Typeface.NORMAL);
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        cols = Math.max(1, getResources().getInteger(R.integer.columns));
        M = W * 6 / 100;
        G = W * 2 / 100;
        P = G * 3 / 4;
        tileW = (W - 2 * M - (cols - 1) * G) / cols;
        tileH = tileW * 9 / 16;
        labelH = Math.round(H * 0.045f);
        tileTotalH = tileH + G / 3 + labelH;
        bandH = tileTotalH + 2 * P;
        rowH = tileTotalH + G;
        pillH = Math.round(H * 0.062f);

        loadApps();

        root = new FrameLayout(this);
        int wp = getResources().getIdentifier("wallpaper", "drawable", getPackageName());
        if (wp != 0) {
            ImageView w = new ImageView(this);
            w.setScaleType(ImageView.ScaleType.CENTER_CROP);
            w.setImageResource(wp);
            root.addView(w, new FrameLayout.LayoutParams(-1, -1));
            View dim = new View(this);
            dim.setBackgroundColor(0x73000000);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        }

        int gridRows = (gridApps.size() + cols - 1) / cols;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        View header = buildHeader(); // sets headerH
        baseGridTop = headerH + bandH + 2 * G;
        gridTop = baseGridTop;
        root.addView(page, new FrameLayout.LayoutParams(W, gridTop + gridRows * rowH + H));
        page.addView(header, new LinearLayout.LayoutParams(W, headerH));

        LinearLayout band = new LinearLayout(this);
        band.setPadding(P, P, P, P);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(W - 2 * M + 2 * P, bandH);
        blp.leftMargin = M - P;
        page.addView(band, blp);
        for (int i = 0; i < dockApps.size(); i++) {
            LinearLayout t = tile(dockApps.get(i), true);
            dockTiles.add(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileTotalH);
            if (i > 0) lp.leftMargin = G;
            band.addView(t, lp);
        }

        page.addView(new View(this), new LinearLayout.LayoutParams(W, 2 * G));
        continueSection = new LinearLayout(this);
        continueSection.setOrientation(LinearLayout.VERTICAL);
        page.addView(continueSection, new LinearLayout.LayoutParams(W, 0));
        for (int r = 0; r < gridRows; r++) {
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(W - 2 * M, rowH);
            llp.leftMargin = M;
            page.addView(line, llp);
            for (int c = 0; c < cols && r * cols + c < gridApps.size(); c++) {
                LinearLayout t = tile(gridApps.get(r * cols + c), false);
                gridTiles.add(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileTotalH);
                if (c > 0) lp.leftMargin = G;
                line.addView(t, lp);
            }
        }

        glass = new Glass(this, Math.max(2f, H / 360f));
        root.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        if (dockApps.isEmpty()) row = gridApps.isEmpty() ? -1 : 1;
        page.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                page.removeOnLayoutChangeListener(this);
                laidOut = true;
                applyFocus(false);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 21 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) refreshWatchNext();
    }

    private void refreshWatchNext() {
        if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) return;
        Set<String> allowed = new HashSet<>();
        for (App app : dockApps) {
            if (!"org.smarttube.stable".equals(app.pkg)) allowed.add(app.pkg);
        }
        int loadId = ++watchNextLoadId;
        watchNextExecutor.execute(() -> {
            List<WatchNext.Program> result;
            try {
                result = WatchNext.load(getContentResolver(), allowed);
            } catch (RuntimeException e) {
                Log.w("TvHome", "Watch Next unavailable", e);
                result = new ArrayList<>();
            }
            List<WatchNext.Program> programs = result;
            runOnUiThread(() -> {
                if (isDestroyed() || loadId != watchNextLoadId) return;
                showWatchNext(programs);
                Log.i("TvHome", "Eligible Continue Watching items: " + programs.size());
            });
        });
    }

    private int firstGridRow() { return continuePrograms.isEmpty() ? 1 : 2; }

    private boolean isContinueRow() { return row == 1 && !continuePrograms.isEmpty(); }

    private void showWatchNext(List<WatchNext.Program> programs) {
        int oldFirstGrid = firstGridRow();
        boolean wasGrid = row >= oldFirstGrid;
        continuePrograms.clear();
        continuePrograms.addAll(programs);
        if (wasGrid) row += firstGridRow() - oldFirstGrid;
        else if (row == 1 && programs.isEmpty()) { row = 0; col = lastDockCol; }
        continueTiles.clear();
        continueSection.removeAllViews();
        int height = 0;
        if (!programs.isEmpty()) {
            TextView heading = text(regular, H * 0.031f, Color.WHITE);
            heading.setText("Continue Watching");
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(W - 2 * M, labelH + G / 2);
            hp.leftMargin = M;
            continueSection.addView(heading, hp);
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(W - 2 * M, rowH);
            rp.leftMargin = M;
            continueSection.addView(line, rp);
            for (WatchNext.Program program : programs) {
                LinearLayout tile = continueTile(program);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(tileW, tileTotalH);
                if (!continueTiles.isEmpty()) tp.leftMargin = G;
                line.addView(tile, tp);
                continueTiles.add(tile);
            }
            height = labelH + G / 2 + rowH;
        }
        continueSection.setLayoutParams(new LinearLayout.LayoutParams(W, height));
        gridTop = baseGridTop + height;
        page.setLayoutParams(new FrameLayout.LayoutParams(W,
                gridTop + ((gridApps.size() + cols - 1) / cols) * rowH + H));
        page.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                page.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (!isDestroyed() && laidOut) applyFocus(false);
            }
        });
    }

    private LinearLayout continueTile(WatchNext.Program program) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(0xFF26262A, tileH * 0.08f));
        card.setClipToOutline(true);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int padding = tileH / 5;
        poster.setPadding(padding, padding, padding, padding);
        for (App app : dockApps) if (app.pkg.equals(program.packageName)) poster.setImageDrawable(app.icon);
        card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xEE000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(G / 2, G / 3, G / 2, G / 3);
        TextView title = text(regular, H * 0.025f, Color.WHITE);
        title.setText(program.title);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        caption.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView progress = text(regular, H * 0.019f, 0xBBFFFFFF);
        progress.setText(program.positionMillis > 0
                ? "Resume at " + DateUtils.formatElapsedTime(program.positionMillis / 1000) : "Resume");
        caption.addView(progress);
        card.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setContentDescription(program.title);
        loadArtwork(poster, program);
        tile.addView(card, new LinearLayout.LayoutParams(tileW, tileH));
        TextView appName = text(regular, H * 0.023f, 0xE6FFFFFF);
        for (App app : dockApps) if (app.pkg.equals(program.packageName)) appName.setText(app.label);
        appName.setGravity(Gravity.CENTER);
        appName.setSingleLine(true);
        appName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, labelH);
        lp.topMargin = G / 3;
        tile.addView(appName, lp);
        return tile;
    }

    private void loadArtwork(ImageView image, WatchNext.Program program) {
        String key = (program.posterUri == null ? "" : program.posterUri) + "\n"
                + (program.thumbnailUri == null ? "" : program.thumbnailUri);
        if (key.trim().isEmpty()) return;
        image.setTag(key);
        Bitmap cached = artworkCache.get(key);
        if (cached != null) { showArtwork(image, cached); return; }
        int width = tileW, height = tileH;
        artworkExecutor.execute(() -> {
            Bitmap loaded = null;
            for (String uri : new String[]{program.posterUri, program.thumbnailUri}) {
                if (Thread.currentThread().isInterrupted()) return;
                if (uri == null || uri.trim().isEmpty()) continue;
                try {
                    loaded = Artwork.load(uri, width, height);
                } catch (java.io.IOException | RuntimeException e) {
                    // Keep the app icon; do not log artwork URLs that may contain access tokens.
                    Log.w("TvHome", "Artwork read failed: " + e.getClass().getSimpleName());
                }
                if (loaded != null) break;
            }
            Bitmap bitmap = loaded;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (bitmap == null) { Log.w("TvHome", "Continue Watching artwork unavailable"); return; }
                artworkCache.put(key, bitmap);
                if (image.isAttachedToWindow() && key.equals(image.getTag())) {
                    showArtwork(image, bitmap);
                    Log.i("TvHome", "Continue Watching artwork loaded");
                }
            });
        });
    }

    private void showArtwork(ImageView image, Bitmap bitmap) {
        image.setPadding(0, 0, 0, 0);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
    }

    private void resumeProgram(WatchNext.Program program) {
        try {
            Intent intent = Intent.parseUri(program.intentUri, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null);
            if (intent.getComponent() != null
                    && !program.packageName.equals(intent.getComponent().getPackageName())) {
                intent.setComponent(null);
            }
            intent.setPackage(program.packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            start(intent);
        } catch (java.net.URISyntaxException e) {
            Toast.makeText(this, "Resume link is not available", Toast.LENGTH_SHORT).show();
        }
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);
        page.setClipChildren(false);

        // Ambient glow above the dock in the focused dock app's colour: a radial gradient, no blur.
        View glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // View is exactly the gradient's diameter, so the gradient fades out before any edge.
        int gr = Math.round(H * 0.6f);
        glowBg.setGradientRadius(gr);
        setGlow(glowColor);
        glow.setBackground(glowBg);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(2 * gr, 2 * gr, Gravity.BOTTOM | Gravity.START);
        glow.setTranslationX(M + W * 0.1f - gr);
        glow.setTranslationY(gr - G);
        header.addView(glow, glp);

        // One line, baseline aligned: date, then time.
        LinearLayout clockBox = new LinearLayout(this);
        clock = text(thin, H * 0.09f, Color.WHITE);
        clock.setIncludeFontPadding(false);
        date = text(light, H * 0.036f, 0xCCFFFFFF);
        clockBox.addView(date);
        LinearLayout.LayoutParams cklp = new LinearLayout.LayoutParams(-2, -2);
        cklp.leftMargin = G;
        clockBox.addView(clock, cklp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clp.leftMargin = M;
        clp.topMargin = M / 2;
        header.addView(clockBox, clp);

        LinearLayout bar = new LinearLayout(this);
        for (String s : new String[]{"Inputs", "Wi-Fi", "Settings"}) {
            TextView t = text(regular, H * 0.03f, Color.WHITE);
            t.setText(s);
            t.setGravity(Gravity.CENTER);
            t.setPadding(pillH * 6 / 10, 0, pillH * 6 / 10, 0);
            t.setBackground(rounded(0x2EFFFFFF, pillH / 2f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, pillH);
            if (!pills.isEmpty()) lp.leftMargin = G / 2;
            bar.addView(t, lp);
            pills.add(t);
        }
        wifiPill = pills.get(1);
        wifiPill.setMaxWidth(Math.round(W * 0.22f));
        wifiPill.setSingleLine(true);
        wifiPill.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.rightMargin = M;
        plp.topMargin = M / 2;
        header.addView(bar, plp);

        // Header is only as tall as the clock line and the pills, so the dock sits right under them.
        clock.setText("00:00");
        date.setText("Wednesday, 30 September");
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        clockBox.measure(unspecified, unspecified);
        headerH = M / 2 + Math.max(clockBox.getMeasuredHeight(), pillH) + G;
        return header;
    }

    private LinearLayout tile(App a, boolean dock) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        ImageView art = new ImageView(this);
        // Icons return to this white drawable when focus leaves them.
        art.setTag(a.mono);
        art.setImageDrawable(a.mono != null ? a.mono : a.icon);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!dock) {
            int p = tileH / 10;
            art.setPadding(p, p, p, p);
        }
        art.setContentDescription(a.label);
        t.addView(art, new LinearLayout.LayoutParams(tileW, tileH));
        TextView label = text(regular, H * 0.028f, 0xE6FFFFFF);
        label.setText(a.label);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTag(dock);
        label.setAlpha(dock ? 0f : 1f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(tileW, labelH);
        llp.topMargin = G / 3;
        t.addView(label, llp);
        return t;
    }

    private TextView text(Typeface tf, float px, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        List<String> hidden = Arrays.asList(getResources().getStringArray(R.array.hidden_apps));
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = ri.loadLabel(pm).toString();
                a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                a.icon = ri.loadIcon(pm);
                a.art = ri.activityInfo.loadBanner(pm);
                a.banner = a.art != null;
                if (!a.banner) a.art = a.icon;
                a.glow = sampleGlow(a.art);
                a.mono = monochrome(a.icon);
                byPkg.put(pkg, a);
            }
        }
        for (String pkg : getResources().getStringArray(R.array.dock_apps)) {
            App a = byPkg.remove(pkg);
            if (a != null && dockApps.size() < cols) dockApps.add(a);
        }
        gridApps.addAll(byPkg.values());
        gridApps.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    /**
     * White glyph for unselected apps. Uses the app's own monochrome layer (Android 13+) when it ships one.
     * Otherwise the icon (adaptive: its foreground layer) is turned into a white mask:
     * - opaque corners (a square icon with its own background): keep what differs from that background;
     * - transparent corners: keep the shape, and cut white details out of a coloured glyph.
     */
    Drawable monochrome(Drawable icon) {
        Drawable src = icon;
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            Drawable m = Build.VERSION.SDK_INT >= 33 ? ad.getMonochrome() : null;
            if (m != null) {
                m = m.mutate();
                m.setTint(Color.WHITE);
                m.setTintMode(PorterDuff.Mode.SRC_IN);
                return m;
            }
            src = ad.getForeground();
        }
        if (src == null) return null;
        int s = 192;
        Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Drawable c = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
        c.setBounds(0, 0, s, s);
        c.draw(new Canvas(bm));
        int[] px = new int[s * s];
        bm.getPixels(px, 0, s, 0, 0, s, s);
        int corner = px[2 * s + 2];
        boolean opaque = Color.alpha(corner) > 200;
        int colored = 0, white = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            if (whiteness(p) > 0.8f) white++; else colored++;
        }
        boolean cutWhite = !opaque && white > 0 && white < colored;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            float a;
            if (opaque) {
                float d = Math.max(Math.abs(Color.red(p) - Color.red(corner)),
                        Math.max(Math.abs(Color.green(p) - Color.green(corner)), Math.abs(Color.blue(p) - Color.blue(corner)))) / 255f;
                a = clamp01((d - 0.15f) / 0.25f);
            } else {
                a = Color.alpha(p) / 255f;
                if (cutWhite) a *= 1f - clamp01((whiteness(p) - 0.7f) / 0.2f);
            }
            px[i] = Color.argb(Math.round(a * 255), 255, 255, 255);
        }
        bm.setPixels(px, 0, s, 0, 0, s, s);
        return new BitmapDrawable(getResources(), bm);
    }

    private static float whiteness(int p) {
        return Math.min(Color.red(p), Math.min(Color.green(p), Color.blue(p))) / 255f;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Saturation-weighted average colour of the artwork, pushed bright for the glow. */
    static int sampleGlow(Drawable d) {
        try {
            Drawable c = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            Bitmap bm = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            c.setBounds(0, 0, 32, 18);
            c.draw(new Canvas(bm));
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, wt = 0;
            for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double w = hsv[1] * hsv[2];
                w *= w;
                r += Color.red(p) * w; g += Color.green(p) * w; b += Color.blue(p) * w; wt += w;
            }
            bm.recycle();
            if (wt < 0.5) return FALLBACK_GLOW;
            Color.colorToHSV(Color.rgb((int) (r / wt), (int) (g / wt), (int) (b / wt)), hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = 1f;
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return FALLBACK_GLOW;
        }
    }

    private void launch(App a) {
        try {
            startActivity(a.launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, a.label + " cannot be opened", Toast.LENGTH_SHORT).show();
        }
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- focus

    private int rowSize(int r) {
        if (r < 0) return pills.size();
        if (r == 0) return dockApps.size();
        if (r == 1 && !continuePrograms.isEmpty()) return continuePrograms.size();
        return Math.max(0, Math.min(cols, gridApps.size() - (r - firstGridRow()) * cols));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ours = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
                || k == KeyEvent.KEYCODE_BACK;
        if (!ours) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
            default: if (e.getRepeatCount() == 0) activate();
        }
        return true;
    }

    private void move(int dx, int dy) {
        if (!laidOut) return;
        if (dropdown != null) {
            if (dy != 0) setDrop(Math.max(0, Math.min(dropItems.size() - 1, dropIdx + dy)));
            else closeDropdown();
            return;
        }
        if (dx != 0) {
            // Row ends stop here: nothing falls through to another row.
            if (row < 0) pill = Math.max(0, Math.min(pills.size() - 1, pill + dx));
            else col = Math.max(0, Math.min(rowSize(row) - 1, col + dx));
        } else if (dy < 0) {
            if (row <= 0) row = -1;
            else {
                row--;
                if (rowSize(row) == 0) row = -1;
                else col = Math.min(col, rowSize(row) - 1);
            }
        } else {
            int next = row + 1;
            while (next <= 0 && rowSize(next) == 0) next++;
            if (rowSize(next) == 0) return;
            col = row < 0 && next == 0 ? lastDockCol : Math.min(col, rowSize(next) - 1);
            row = next;
        }
        if (row == 0) lastDockCol = col;
        applyFocus(true);
    }

    private void back() {
        if (dropdown != null) { closeDropdown(); return; }
        if (row != 0 && !dockApps.isEmpty()) {
            row = 0;
            col = lastDockCol;
            applyFocus(true);
        }
    }

    private void activate() {
        if (dropdown != null) {
            Runnable r = dropActions.get(dropIdx);
            closeDropdown();
            r.run();
            return;
        }
        if (row < 0) {
            if (pill == 0) openDropdown();
            else if (pill == 1) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else start(new Intent(Settings.ACTION_SETTINGS));
        } else if (row == 0) {
            launch(dockApps.get(col));
        } else if (isContinueRow()) {
            resumeProgram(continuePrograms.get(col));
        } else {
            launch(gridApps.get((row - firstGridRow()) * cols + col));
        }
    }

    private void applyFocus(boolean animate) {
        if (!laidOut) return;
        if (row >= 0 && rowSize(row) == 0) row = -1;
        if (row >= 0) col = Math.max(0, Math.min(col, rowSize(row) - 1));
        pill = Math.max(0, Math.min(pill, pills.size() - 1));

        int dur = animate ? 180 : 0;
        if (focusedArt != null) {
            if (focusedArt.getTag() instanceof Drawable) {
                ((ImageView) focusedArt).setImageDrawable((Drawable) focusedArt.getTag());
            }
            focusedArt.animate().scaleX(1f).scaleY(1f).setDuration(dur);
            focusedLabel.animate().alpha(Boolean.TRUE.equals(focusedLabel.getTag()) ? 0f : 1f).setDuration(dur);
            focusedArt = focusedLabel = null;
        }

        // Grid rows scroll so the focused row sits mid-screen; dock, Continue Watching and top bar keep the page at rest.
        pageTarget = row < firstGridRow() ? 0
                : Math.min(0, -(gridTop + (row - firstGridRow()) * rowH - (H - tileTotalH) / 2f));
        page.animate().translationY(pageTarget).setDuration(animate ? 320 : 0)
                .setInterpolator(new DecelerateInterpolator(1.6f));

        if (row < 0) {
            TextView p = pills.get(pill);
            float pad = G / 6f;
            RectF r = rectOf(p, 1f);
            r.inset(-pad, -pad);
            glass.moveTo(r, pillH / 2f + pad, animate);
        } else {
            LinearLayout t = row == 0 ? dockTiles.get(col) : isContinueRow() ? continueTiles.get(col)
                    : gridTiles.get((row - firstGridRow()) * cols + col);
            focusedArt = t.getChildAt(0);
            focusedLabel = t.getChildAt(1);
            if (!isContinueRow()) {
                App a = row == 0 ? dockApps.get(col) : gridApps.get((row - firstGridRow()) * cols + col);
                ((ImageView) focusedArt).setImageDrawable(a.icon);
            }
            focusedArt.animate().scaleX(SCALE).scaleY(SCALE).setDuration(dur);
            focusedLabel.animate().alpha(1f).setDuration(dur);
            float pad = G / 5f;
            RectF r = rectOf(focusedArt, SCALE);
            r.inset(-pad, -pad);
            glass.moveTo(r, tileH * 0.08f * SCALE + pad, animate);
            if (row == 0) showGlow(dockApps.get(col), animate);
        }
    }

    /** Where v will be once the page finishes scrolling, scaled about its centre (in root coordinates). */
    private RectF rectOf(View v, float scale) {
        float x = 0, y = 0;
        for (View p = v; p != page; p = (View) p.getParent()) { x += p.getLeft(); y += p.getTop(); }
        x += page.getLeft();
        y += page.getTop() + pageTarget;
        float dw = v.getWidth() * (scale - 1) / 2, dh = v.getHeight() * (scale - 1) / 2;
        return new RectF(x - dw, y - dh, x + v.getWidth() + dw, y + v.getHeight() + dh);
    }

    private void showGlow(App a, boolean animate) {
        if (a.glow == glowColor) return;
        if (!animate) { setGlow(glowColor = a.glow); return; }
        ValueAnimator va = ValueAnimator.ofArgb(glowColor, a.glow);
        va.setDuration(400);
        va.addUpdateListener(v -> setGlow((int) v.getAnimatedValue()));
        va.start();
        glowColor = a.glow;
    }

    private void setGlow(int c) {
        glowBg.setColors(new int[]{(c & 0x00FFFFFF) | 0x66000000, (c & 0x00FFFFFF) | 0x22000000, 0x00000000});
    }

    // ---------------------------------------------------------------- inputs dropdown

    private void openDropdown() {
        dropItems.clear();
        dropActions.clear();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (!i.isPassthroughInput() || i.getParentId() != null || i.isHidden(this)) continue;
                CharSequence l = i.loadCustomLabel(this);
                if (TextUtils.isEmpty(l)) l = i.loadLabel(this);
                Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(i.getId()));
                addDrop(l, () -> start(view));
            }
        }
        String live = getString(R.string.live_tv_package);
        PackageManager pm = getPackageManager();
        Intent li = pm.getLeanbackLaunchIntentForPackage(live);
        if (li == null) li = pm.getLaunchIntentForPackage(live);
        if (li != null) { Intent liveIntent = li; addDrop("Live TV", () -> start(liveIntent)); }
        if (dropItems.isEmpty()) addDrop("No inputs found", () -> { });

        dropdown = new LinearLayout(this);
        dropdown.setOrientation(LinearLayout.VERTICAL);
        int pad = G / 3;
        dropdown.setPadding(pad, pad, pad, pad);
        dropdown.setBackground(rounded(0xF0202024, pillH * 0.45f));
        for (TextView t : dropItems) dropdown.addView(t, new LinearLayout.LayoutParams(-1, pillH));
        RectF anchor = rectOf(pills.get(0), 1f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(W * 0.2f), -2);
        lp.leftMargin = Math.round(Math.min(anchor.left, W - M - W * 0.2f));
        lp.topMargin = Math.round(anchor.bottom + G / 2f);
        root.addView(dropdown, root.indexOfChild(glass), lp);
        dropdown.setAlpha(0f);
        dropdown.animate().alpha(1f).setDuration(150);
        dropdown.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                setDrop(0);
            }
        });
    }

    private void addDrop(CharSequence label, Runnable action) {
        TextView t = text(regular, H * 0.03f, Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(pillH / 2, 0, pillH / 2, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        dropItems.add(t);
        dropActions.add(action);
    }

    private void setDrop(int i) {
        if (dropdown == null) return;
        dropIdx = i;
        TextView t = dropItems.get(i);
        float x = dropdown.getLeft() + t.getLeft(), y = dropdown.getTop() + t.getTop();
        glass.moveTo(new RectF(x, y, x + t.getWidth(), y + t.getHeight()), pillH * 0.3f, true);
    }

    private void closeDropdown() {
        if (dropdown == null) return;
        root.removeView(dropdown);
        dropdown = null;
        applyFocus(true);
    }

    // ---------------------------------------------------------------- clock and network

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    private String ssid(NetworkCapabilities c) {
        String s = null;
        if (Build.VERSION.SDK_INT >= 29 && c.getTransportInfo() instanceof WifiInfo) s = ((WifiInfo) c.getTransportInfo()).getSSID();
        if (s == null || s.contains("<unknown")) {
            WifiInfo wi = getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
            s = wi != null ? wi.getSSID() : null;
        }
        if (s == null || s.contains("<unknown")) return "Wi-Fi";
        return s.replace("\"", "");
    }

    // ---------------------------------------------------------------- glass highlight

    /** One translucent highlight that springs between targets, drawn above everything. */
    static final class Glass extends View {
        private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float radius, rFrom, rTo;
        private boolean shown;
        private ValueAnimator anim;

        Glass(Context c, float strokePx) {
            super(c);
            fill.setColor(0x1FFFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokePx);
            stroke.setColor(0x8CFFFFFF);
        }

        void moveTo(RectF target, float r, boolean animate) {
            if (anim != null) anim.cancel();
            if (!shown || !animate) {
                cur.set(target);
                radius = r;
                shown = true;
                invalidate();
                return;
            }
            from.set(cur);
            to.set(target);
            rFrom = radius;
            rTo = r;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(340);
            anim.setInterpolator(new OvershootInterpolator(1.15f));
            anim.addUpdateListener(v -> {
                float f = (float) v.getAnimatedValue();
                cur.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                        from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
                radius = Math.max(0f, rFrom + (rTo - rFrom) * f);
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!shown) return;
            c.drawRoundRect(cur, radius, radius, fill);
            c.drawRoundRect(cur, radius, radius, stroke);
        }
    }

}
