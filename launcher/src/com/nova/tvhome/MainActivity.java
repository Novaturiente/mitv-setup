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
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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

/** Apple TV style home screen. One activity, framework views only, own D-pad focus model. */
public class MainActivity extends Activity {
    private static final float SCALE = 1.08f;
    private static final int FALLBACK_GLOW = 0xFF5A7BFF;

    private static final class App {
        String pkg, label;
        Intent launch;
        Drawable art, mono;
        boolean banner;
        int glow;
    }

    private final List<App> dockApps = new ArrayList<>(), gridApps = new ArrayList<>();
    private final List<LinearLayout> dockTiles = new ArrayList<>(), gridTiles = new ArrayList<>();
    private final List<TextView> pills = new ArrayList<>();

    private FrameLayout root;
    private LinearLayout page;
    private Glass glass;
    private TextView clock, date, wifiPill;
    private GradientDrawable glowBg;
    private Typeface thin, light, regular;

    private int W, H, M, G, P, cols, tileW, tileH, labelH, tileTotalH, bandH, headerH, rowH, gridTop, pillH;
    // row -1 = top bar, 0 = dock, 1.. = grid rows. Kept across app launches: "remember last tile".
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
    }

    @Override
    protected void onDestroy() {
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
        gridTop = headerH + bandH + 2 * G;
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
        if (dock && a.mono != null) {
            // Dock: white glyph straight on the band, no card.
            art.setImageDrawable(a.mono);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            art.setBackground(rounded(0xFF26262A, tileH * 0.08f));
            art.setClipToOutline(true);
            art.setImageDrawable(a.art);
            if (a.banner) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int p = tileH / 5;
                art.setPadding(p, p, p, p);
            }
        }
        art.setContentDescription(a.label);
        t.addView(art, new LinearLayout.LayoutParams(tileW, tileH));
        TextView label = text(regular, H * 0.028f, 0xE6FFFFFF);
        label.setText(a.label);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAlpha(0f);
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
        List<String> dockPkgs = Arrays.asList(getResources().getStringArray(R.array.dock_apps));
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
                a.art = ri.activityInfo.loadBanner(pm);
                a.banner = a.art != null;
                if (!a.banner) a.art = ri.loadIcon(pm);
                a.glow = sampleGlow(a.art);
                if (dockPkgs.contains(pkg)) a.mono = monochrome(ri.loadIcon(pm));
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
     * White glyph for the dock. Uses the app's own monochrome layer (Android 13+) when it ships one.
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
        return Math.max(0, Math.min(cols, gridApps.size() - (r - 1) * cols));
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
        } else {
            launch(gridApps.get((row - 1) * cols + col));
        }
    }

    private void applyFocus(boolean animate) {
        if (!laidOut) return;
        if (row >= 0 && rowSize(row) == 0) row = -1;
        if (row >= 0) col = Math.max(0, Math.min(col, rowSize(row) - 1));
        pill = Math.max(0, Math.min(pill, pills.size() - 1));

        int dur = animate ? 180 : 0;
        if (focusedArt != null) {
            focusedArt.animate().scaleX(1f).scaleY(1f).setDuration(dur);
            focusedLabel.animate().alpha(0f).setDuration(dur);
            focusedArt = focusedLabel = null;
        }

        // Grid rows scroll so the focused row sits mid-screen; dock and top bar keep the page at rest.
        pageTarget = row <= 0 ? 0 : Math.min(0, -(gridTop + (row - 1) * rowH - (H - tileTotalH) / 2f));
        page.animate().translationY(pageTarget).setDuration(animate ? 320 : 0)
                .setInterpolator(new DecelerateInterpolator(1.6f));

        if (row < 0) {
            TextView p = pills.get(pill);
            float pad = G / 6f;
            RectF r = rectOf(p, 1f);
            r.inset(-pad, -pad);
            glass.moveTo(r, pillH / 2f + pad, animate);
        } else {
            LinearLayout t = (row == 0 ? dockTiles : gridTiles).get(row == 0 ? col : (row - 1) * cols + col);
            focusedArt = t.getChildAt(0);
            focusedLabel = t.getChildAt(1);
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
