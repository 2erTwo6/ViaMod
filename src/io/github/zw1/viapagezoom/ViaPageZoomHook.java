package io.github.zw1.viapagezoom;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that adds a "页面缩放" (PageZoom) tool to the *original* Via browser
 * (mark.via.gp), without repackaging or re-signing the app.
 *
 * Behaviour (identical to the ViaMod APK patch):
 *   - adds tool id 42 to the toolbox menu (default + custom menu)
 *   - tapping it opens a slider dialog (50% .. 200%), live preview while dragging
 *   - dismiss-as-confirm: closing the dialog in any way saves the current value
 *   - "恢复默认" clears the per-host value and resets to 100%
 *   - the value is remembered per host (SharedPreferences "viamod_pagezoom")
 *   - on every page load the stored zoom is re-applied
 *
 * Injection uses WebView.loadUrl("javascript:...") on document.documentElement.style.zoom.
 * evaluateJavascript does NOT work in Via's WebView wrapper (verified), so never switch.
 *
 * All Via classes below are R8-obfuscated names valid for Via 7.3.3 (versionCode 20260823).
 */
public class ViaPageZoomHook implements IXposedHookLoadPackage {

    private static final String TAG = "ViaPageZoom";
    private static final String TARGET_PKG = "mark.via.gp";

    private static final int TOOL_ID = 42;
    private static final String PREFS_NAME = "viamod_pagezoom";
    private static final String TITLE = "页面缩放";
    private static final String BTN_RESET = "恢复默认";
    private static final String MSG_NO_PAGE = "请先打开网页";
    private static final int MIN_ZOOM = 50;
    private static final int MAX_ZOOM = 200;
    private static final int DEFAULT_ZOOM = 100;

    // Via 7.3.3 obfuscated entry points
    private static final String CLS_FRAGMENT = "c8.s6";        // main browser fragment, C9(II) = tool click
    private static final String CLS_TOOL_FACTORY = "i8.l";     // a(Context,I) = tool item, c() = valid ids
    private static final String CLS_SETTINGS = "w9.k";         // s0() = displayed toolbox menu ids
    private static final String CLS_MENU_ITEM = "h8.a";        // (id, Drawable, title, enabled)
    private static final String CLS_WEBVIEW_CLIENT = "p4.j";   // real per-tab WebViewClient
    private static final String CLS_DIALOG = "w5.k";           // app dialog builder
    private static final String CLS_ICON_FACTORY = "lb.b";     // a(Context, iconRes, tintNameRes) -> Drawable
    private static final String CLS_DRAWABLE_IDS = "x7.o";     // R.drawable fields
    private static final String CLS_STRING_IDS = "x7.u";       // R.string fields
    private static final String FIELD_ICON = "h0";             // drawable/c6  (font-size icon, reused)
    private static final String FIELD_TINT = "se";             // string/wk   (icon tint name)

    private static ClassLoader sClassLoader;
    private static volatile boolean resetPending = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        sClassLoader = lpparam.classLoader;
        logMsg("loading into " + lpparam.packageName + " process=" + lpparam.processName);

        try { hookMenuDefault(sClassLoader); } catch (Throwable t) { logErr("hookMenuDefault", t); }
        try { hookToolFactory(sClassLoader); } catch (Throwable t) { logErr("hookToolFactory", t); }
        try { hookValidIds(sClassLoader); } catch (Throwable t) { logErr("hookValidIds", t); }
        try { hookClickDispatch(sClassLoader); } catch (Throwable t) { logErr("hookClickDispatch", t); }
        try { hookWebViewClient(sClassLoader); } catch (Throwable t) { logErr("hookWebViewClient", t); }

        logMsg("hooks installed");
    }

    /** Make tool id 42 part of the toolbox menu (works for default and customised menus). */
    private static void hookMenuDefault(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_SETTINGS, cl, "s0", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int[] ids = (int[]) param.getResult();
                    if (ids == null) {
                        ids = new int[0];
                    }
                    for (int id : ids) {
                        if (id == TOOL_ID) {
                            return;
                        }
                    }
                    int[] out = Arrays.copyOf(ids, ids.length + 1);
                    out[ids.length] = TOOL_ID;
                    param.setResult(out);
                    logMsg("toolbox menu extended with id " + TOOL_ID + " (total " + out.length + ")");
                } catch (Throwable t) {
                    logErr("s0 after", t);
                }
            }
        });
    }

    /** Serve our own menu item when the toolbox asks for id 42. */
    private static void hookToolFactory(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_TOOL_FACTORY, cl, "a",
                Context.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int id = (Integer) param.args[1];
                        if (id != TOOL_ID) {
                            return;
                        }
                        try {
                            Context ctx = (Context) param.args[0];
                            Object item = buildMenuItem(ctx, cl);
                            if (item != null) {
                                param.setResult(item);
                                logMsg("tool factory served id " + TOOL_ID);
                            }
                        } catch (Throwable t) {
                            logErr("tool factory", t);
                        }
                    }
                });
    }

    /** Keep id 42 a "known" tool so the customise-menu screen accepts it. */
    @SuppressWarnings("unchecked")
    private static void hookValidIds(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_TOOL_FACTORY, cl, "c", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object result = param.getResult();
                    if (result instanceof Set) {
                        ((Set<Object>) result).add(TOOL_ID);
                    }
                } catch (Throwable t) {
                    logErr("c after", t);
                }
            }
        });
    }

    /** Intercept the tool tap: consume it and show our dialog. */
    private static void hookClickDispatch(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_FRAGMENT, cl, "C9",
                int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int id = (Integer) param.args[0];
                        if (id != TOOL_ID) {
                            return;
                        }
                        param.setResult(null); // void method: skip original dispatch
                        try {
                            Object fragment = param.thisObject;
                            Context ctx = (Context) XposedHelpers.callMethod(fragment, "I");
                            WebView webView = currentWebView(fragment);
                            showDialog(ctx, webView);
                        } catch (Throwable t) {
                            logErr("click dispatch", t);
                        }
                    }
                });
    }

    /** Apply the stored zoom on every page load. */
    private static void hookWebViewClient(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_WEBVIEW_CLIENT, cl, "onPageStarted",
                WebView.class, String.class, Bitmap.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        apply((WebView) param.args[0], (String) param.args[1]);
                    }
                });

        XposedHelpers.findAndHookMethod(CLS_WEBVIEW_CLIENT, cl, "onPageFinished",
                WebView.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        apply((WebView) param.args[0], (String) param.args[1]);
                    }
                });

        // p4.j does not override onPageCommitVisible; catch it on the base class and filter
        // by concrete client class, so late/SPA navigations are covered too.
        try {
            XposedBridge.hookAllMethods(WebViewClient.class, "onPageCommitVisible", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (self == null || !CLS_WEBVIEW_CLIENT.equals(self.getClass().getName())) {
                            return;
                        }
                        apply((WebView) param.args[0], (String) param.args[1]);
                    } catch (Throwable t) {
                        logErr("onPageCommitVisible", t);
                    }
                }
            });
        } catch (Throwable t) {
            logErr("hook onPageCommitVisible", t);
        }
    }

    // ---------------------------------------------------------------- zoom logic

    private static void apply(WebView webView, String url) {
        try {
            if (webView == null) {
                return;
            }
            String host = hostOf(url);
            if (host == null) {
                return;
            }
            Context ctx = webView.getContext();
            if (ctx == null) {
                return;
            }
            int zoom = prefs(ctx).getInt(host, -1);
            if (zoom <= 0) {
                return;
            }
            applyValue(webView, zoom);
        } catch (Throwable t) {
            logErr("apply", t);
        }
    }

    /** Inject (or clear, when zoom <= 0) the layout zoom into the current document. */
    private static void applyValue(final WebView webView, final int zoom) {
        if (webView == null) {
            return;
        }
        runOnUi(new Runnable() {
            @Override
            public void run() {
                try {
                    String value = zoom > 0 ? (zoom + "/100") : "''";
                    // MUST be loadUrl: evaluateJavascript has no effect in Via's WebView wrapper.
                    webView.loadUrl("javascript:(function(){try{document.documentElement.style.zoom="
                            + value + ";}catch(e){}})();");
                } catch (Throwable t) {
                    logErr("applyValue", t);
                }
            }
        });
    }

    private static void showDialog(final Context ctx, final WebView webView) {
        if (ctx == null || webView == null) {
            toast(ctx, MSG_NO_PAGE);
            return;
        }
        final String host = hostOf(webView.getUrl());
        if (host == null) {
            toast(ctx, MSG_NO_PAGE);
            return;
        }

        resetPending = false;
        final SharedPreferences sp = prefs(ctx);
        int zoom = clamp(sp.getInt(host, DEFAULT_ZOOM));

        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int gap = (int) (8 * density);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, gap, pad, pad);

        final TextView label = new TextView(ctx);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(16f);
        setLabel(label, zoom);
        layout.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final SeekBar seek = new SeekBar(ctx);
        seek.setMax(MAX_ZOOM - MIN_ZOOM);
        seek.setProgress(zoom - MIN_ZOOM);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        seekParams.setMargins(gap, gap, gap, gap);
        layout.addView(seek, seekParams);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress + MIN_ZOOM;
                setLabel(label, value);
                applyValue(webView, value); // live preview
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });

        View.OnClickListener resetListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Runs before the dialog auto-dismisses; the flag stops onDismiss re-saving.
                resetPending = true;
                sp.edit().remove(host).apply();
                applyValue(webView, 0);
                logMsg("zoom reset for " + host);
            }
        };

        DialogInterface.OnDismissListener dismissListener = new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (resetPending) {
                    resetPending = false;
                    return;
                }
                int value = seek.getProgress() + MIN_ZOOM;
                sp.edit().putInt(host, value).apply();
                applyValue(webView, value);
                logMsg("zoom saved for " + host + " = " + value + "%");
            }
        };

        try {
            Class<?> dialogCls = XposedHelpers.findClass(CLS_DIALOG, sClassLoader);
            Object builder = XposedHelpers.callStaticMethod(dialogCls, "l", ctx);
            builder = XposedHelpers.callMethod(builder, "e0", TITLE);
            builder = XposedHelpers.callMethod(builder, "y", layout);
            builder = XposedHelpers.callMethod(builder, "O", BTN_RESET, resetListener);
            builder = XposedHelpers.callMethod(builder, "U", dismissListener);
            XposedHelpers.callMethod(builder, "f0");
            logMsg("dialog shown for " + host);
        } catch (Throwable t) {
            logErr("showDialog", t);
            toast(ctx, "页面缩放对话框打开失败：" + t);
        }
    }

    private static Object buildMenuItem(Context ctx, ClassLoader cl) {
        Class<?> itemCls = XposedHelpers.findClass(CLS_MENU_ITEM, cl);
        Drawable icon = loadIcon(ctx, cl);
        return XposedHelpers.newInstance(itemCls,
                new Class<?>[]{int.class, Drawable.class, String.class, boolean.class},
                TOOL_ID, icon, TITLE, Boolean.TRUE);
    }

    private static Drawable loadIcon(Context ctx, ClassLoader cl) {
        try {
            Class<?> iconFactory = XposedHelpers.findClass(CLS_ICON_FACTORY, cl);
            Class<?> drawables = XposedHelpers.findClass(CLS_DRAWABLE_IDS, cl);
            Class<?> strings = XposedHelpers.findClass(CLS_STRING_IDS, cl);
            int iconRes = XposedHelpers.getStaticIntField(drawables, FIELD_ICON);
            int tintRes = XposedHelpers.getStaticIntField(strings, FIELD_TINT);
            return (Drawable) XposedHelpers.callStaticMethod(iconFactory, "a", ctx, iconRes, tintRes);
        } catch (Throwable t) {
            logErr("loadIcon (falling back to system icon)", t);
            try {
                return ctx.getResources().getDrawable(android.R.drawable.ic_menu_zoom);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static WebView currentWebView(Object fragment) {
        try {
            Object tab = XposedHelpers.callMethod(fragment, "d");
            if (tab != null) {
                Object wv = XposedHelpers.callMethod(tab, "p");
                if (wv instanceof WebView) {
                    return (WebView) wv;
                }
            }
        } catch (Throwable t) {
            logErr("currentWebView via tab", t);
        }
        try {
            Object wv = XposedHelpers.callMethod(fragment, "C8");
            if (wv instanceof WebView) {
                return (WebView) wv;
            }
        } catch (Throwable t) {
            logErr("currentWebView via C8", t);
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) {
                return null;
            }
            host = host.trim().toLowerCase();
            return host.isEmpty() ? null : host;
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void setLabel(TextView tv, int zoom) {
        tv.setText("缩放比例：" + zoom + "%");
    }

    private static int clamp(int zoom) {
        if (zoom < MIN_ZOOM) {
            return MIN_ZOOM;
        }
        if (zoom > MAX_ZOOM) {
            return MAX_ZOOM;
        }
        return zoom;
    }

    private static void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    private static void toast(Context ctx, String msg) {
        if (ctx == null) {
            return;
        }
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            logErr("toast", t);
        }
    }

    private static void logMsg(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logErr(String where, Throwable t) {
        XposedBridge.log(TAG + " [" + where + "] " + t);
        XposedBridge.log(t);
    }
}
