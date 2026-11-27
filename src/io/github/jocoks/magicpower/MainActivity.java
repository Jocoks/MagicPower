package io.github.jocoks.magicpower;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 发热自检页面入口。
 *
 * <p>检查本身要读 /proc 和 /sys，普通应用受 hidepid 限制看不到别的进程，
 * 所以页面背后是一个内置的 Go 服务（assets/magicpower-check）。首次打开时：
 * <ol>
 *   <li>把二进制从 assets 释放到应用私有目录；</li>
 *   <li>用 su 复制到 /data/local/tmp 并以后台会话启动；</li>
 *   <li>等 127.0.0.1:8787 就绪后，WebView 打开页面。</li>
 * </ol>
 * 模块本身的功能（HookEntry）不受影响，两者共存于同一个 APK。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MagicPower";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8787;
    private static final String BASE = "http://" + HOST + ":" + PORT + "/";
    private static final String BIN = "magicpower-check";
    private static final String TMP_BIN = "/data/local/tmp/" + BIN;

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        // 不显式给 MATCH_PARENT 时 WebView 可能按内容宽度收缩，出现空白。
        webView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 不开启这两项时 WebView 会按桌面宽度（约 980px）渲染再整体缩小，
        // 手机上看起来就是"桌面版缩略图"，viewport meta 也不生效。
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        // 系统深色模式下 WebView 会对页面做强制的算法反色，把浅色页面染黑。
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else {
            // API 29-32 用 setForceDark(FORCE_DARK_OFF=0) 关闭强制反色。
            // 部分 android.jar 里没有这个方法/常量，用反射避免编译期依赖。
            try {
                java.lang.reflect.Method m = WebView.class.getMethod("setForceDark", int.class);
                m.invoke(webView, 0);
            } catch (Throwable ignored) {
                // 拿不到就依赖页面本身的 color-scheme: light
            }
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "onPageFinished: " + url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) {
                    return;
                }
                String desc = String.valueOf(error.getDescription());
                Log.e(TAG, "onReceivedError: " + desc + " url=" + request.getUrl());
                // 任何加载失败都落到可见的错误页，绝不再留给用户一片白屏。
                loadBootHtml("页面加载失败：" + desc
                        + "<br><br>检查服务可能还没启动。<br>"
                        + "点下方「重试」；若没反应，返回后重新进入本应用即可重新部署。"
                        + "<br>反复失败请确认已授予 root 权限。");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("MagicPower")
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) { result.cancel(); }
                        })
                        .show();
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(), "MP");
        setContentView(webView);

        loadBootHtml("正在启动检查服务…");
        startAsync();
    }

    private void startAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = ensureServer();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error == null) {
                            webView.loadUrl(BASE);
                        } else {
                            loadBootHtml(error);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 确保检查服务在跑。返回 null 表示就绪，否则返回给用户看的错误说明。
     */
    private String ensureServer() {
        int versionCode = 0;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
            // 拿不到版本号就当作 0，强制走一次部署
        }
        SharedPreferences prefs = getSharedPreferences("mp", MODE_PRIVATE);
        int deployed = prefs.getInt("deployed_vc", -1);

        // 服务已在跑、且是当前这个 APK 版本部署的，直接复用。
        // 否则（首次安装 / APK 升级 / 手机重启后服务没了）重新部署，
        // 部署要经过 su，用户会看到 root 授权流程。
        if (probe() && deployed == versionCode) {
            return null;
        }

        try {
            File local = new File(getFilesDir(), BIN);
            // 不能用文件长度判断是否需要更新：代码微调后二进制长度可能完全相同，
            // 会导致 APK 升级了却仍复用旧服务。以 versionCode 为准。
            if (!local.exists() || deployed != versionCode) {
                extractAsset(BIN, local);
            }

            // 先清掉旧实例：按 exe 精确匹配，避免误杀别的进程。
            String cmd = "for p in /proc/[0-9]*; do e=$(readlink $p/exe 2>/dev/null); "
                    + "case \"$e\" in " + TMP_BIN + ") kill -9 ${p#/proc/} 2>/dev/null;; esac; done; "
                    + "cp '" + local.getAbsolutePath() + "' " + TMP_BIN
                    + " && chmod 755 " + TMP_BIN
                    + " && (setsid " + TMP_BIN + " -addr " + HOST + ":" + PORT
                    + " </dev/null >/data/local/tmp/magicpower-web.log 2>&1 &)";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();

            for (int i = 0; i < 60; i++) {
                Thread.sleep(250);
                if (probe()) {
                    prefs.edit().putInt("deployed_vc", versionCode).apply();
                    return null;
                }
            }
            return "检查服务启动超时。<br><br>请确认 KernelSU 已授予本应用 root 权限"
                    + "（首次打开会弹出授权框）。<br>授权后点「重试」，"
                    + "若没反应就返回再重新进入。";
        } catch (Exception e) {
            Log.e(TAG, "ensureServer failed", e);
            return "启动失败：" + e.getMessage() + "<br><br>需要 root 才能读取系统进程。";
        }
    }

    private boolean probe() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE).openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private long assetSize(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            return in.available();
        } catch (Exception e) {
            return -1;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void extractAsset(String name, File out) throws Exception {
        InputStream in = null;
        OutputStream os = null;
        try {
            in = getAssets().open(name);
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            if (os != null) { try { os.close(); } catch (Exception ignored) { } }
            if (in != null) { try { in.close(); } catch (Exception ignored) { } }
        }
    }

    /**
     * loadData 的 encoding 传 null 会导致 WebView 渲染空白页，
     * 统一走 loadDataWithBaseURL 并显式指定 utf-8。
     */
    private void loadBootHtml(String message) {
        webView.loadDataWithBaseURL(null, bootHtml(message), "text/html", "utf-8", null);
    }

    private String bootHtml(String message) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "body{font-family:-apple-system,'PingFang SC',sans-serif;background:#f4f5f7;"
                + "color:#1f2329;margin:0;padding:56px 24px;text-align:center}"
                + "h2{font-size:18px;margin:0 0 14px}"
                + "p{color:#6b7280;font-size:14px;line-height:1.8;margin:0}"
                + "button{margin-top:22px;padding:10px 26px;border:0;border-radius:8px;"
                + "background:#07c160;color:#fff;font-size:14px;font-weight:600}"
                + "</style></head><body><h2>MagicPower</h2><p>" + message + "</p>"
                + "<button onclick='MP.retry()'>重试</button></body></html>";
    }

    /** 供启动失败页上的「重试」按钮调用。 */
    private class Bridge {
        @JavascriptInterface
        public void retry() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    loadBootHtml("正在启动检查服务…");
                    startAsync();
                }
            });
        }
    }

    /**
     * 返回键：在功能页回到首页，在首页直接退出。
     * 不能只看 canGoBack —— 那样在首页按返回会被退回刚才的功能页，永远退不出去。
     */
    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        String url = webView.getUrl();
        if (url != null && url.contains("#/thermal")) {
            webView.evaluateJavascript("location.hash='#/'", null);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // WebView 不销毁会泄漏整个页面和渲染进程。
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.setWebChromeClient(null);
                webView.destroy();
            } catch (Throwable ignored) {
                // 销毁失败不影响退出
            }
            webView = null;
        }
        super.onDestroy();
    }
}
