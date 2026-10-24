package com.nova.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remaps the remote's PatchWall button to Live TV. The firmware's own handler for that key
 * (partnercustomizer's GlobalKeyReceiver) only opens a hard-coded PatchWall package, so the key
 * is taken here, before the system dispatches it. Every other key passes through untouched.
 */
public class KeyService extends AccessibilityService {
    private int key;

    @Override
    protected void onServiceConnected() {
        key = getResources().getInteger(R.integer.live_tv_keycode);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != key) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            PackageManager pm = getPackageManager();
            String pkg = getString(R.string.live_tv_package);
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                try {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ignored) { }
            }
        }
        return true; // swallow down and up, so the PatchWall handler never sees the key
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
}
