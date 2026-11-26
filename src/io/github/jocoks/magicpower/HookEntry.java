package io.github.jocoks.magicpower;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.database.Cursor;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 电池页耗电排行：百分比旁显示「前台 App + 该 App 活跃时段的屏幕」估算功率。
 *
 * <p>注入点：{@code com.hihonor.systemmanager.power.ui.k#onBindViewHolder}（MagicOS 11 / 管家 18.x）。
 * <ul>
 *   <li>App 前台功率：PowerUsageState.getFgPower()（mA·s）/ getFgTime()（ms）。</li>
 *   <li>短样本修正：fgTime 不足 2 分钟时向“列表整体前台功率”收缩，避免 5.5W 这种尖刺。</li>
 *   <li>屏幕功率：用管家自己的 smartpowerprovider 小时级数据，按该 App 每小时前台能耗加权，
 *       取它活跃时段的屏幕平均功率，而不是全天一个常数。</li>
 *   <li>真实占比 &lt;1% 的条目不显示功率。</li>
 * </ul>
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "LspBatteryPower";
    private static final String TARGET_PKG = "com.hihonor.systemmanager";
    private static final String ADAPTER_CLASS = "com.hihonor.systemmanager.power.ui.k";

    private static final String POWER_AUTHORITY = "com.hihonor.android.smartpowerprovider";
    private static final Uri HISTORY_URI = Uri.parse("content://" + POWER_AUTHORITY + "/batterystatisticshistory");
    private static final Uri SCREEN_ON_URI = Uri.parse("content://" + POWER_AUTHORITY + "/screenontime");

    private static final double NOMINAL_VOLTAGE = 3.8d;
    private static final long DAY_MS = 86400000L;
    private static final long HOUR_MS = 3600000L;
    private static final int HOURS = 24;
    /** 前台样本少于该值时向整体均值收缩。 */
    private static final double SHRINK_SECONDS = 120d;

    private static volatile boolean sHooked;

    private static final Object CACHE_LOCK = new Object();
    private static final Map<Long, DayScreen> DAY_SCREEN = new HashMap<>();
    private static final Map<String, Double> APP_SCREEN = new HashMap<>();
    private static List<?> sTotalList;
    private static double sTotalRaw;
    private static double sPriorWatts;
    private static double sPriorBgWatts;
    private static volatile long sWindowStart;
    private static volatile long sWindowMs;
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*[–—~-]\\s*(\\d{1,2}):(\\d{2})");

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }
        install(param.getClassLoader());
    }

    private void install(ClassLoader cl) {
        if (cl == null || sHooked) {
            return;
        }
        synchronized (HookEntry.class) {
            if (sHooked) {
                return;
            }
            try {
                Class<?> adapter = Class.forName(ADAPTER_CLASS, false, cl);
                Method bind = findBindMethod(adapter);
                if (bind == null) {
                    log(Log.ERROR, TAG, "onBindViewHolder(ViewHolder,int) not found in " + ADAPTER_CLASS);
                    return;
                }
                bind.setAccessible(true);
                hook(bind)
                        .setId("percent-power")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(new Hooker() {
                            @Override
                            public Object intercept(Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                try {
                                    decorate(chain.getThisObject(), chain.getArg(0), chain.getArg(1));
                                } catch (Throwable t) {
                                    log(Log.ERROR, TAG, "decorate failed", t);
                                }
                                return result;
                            }
                        });
                hookWindow(cl);
                hookModelLoad(cl);
                hookTimeRange();
                sHooked = true;
                log(Log.INFO, TAG, "hooked " + ADAPTER_CLASS + ".onBindViewHolder");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook failed", t);
            }
        }
    }

    /**
     * ja6.l(start, windowMs, boolean) 是列表加载入口：24h 时 windowMs=86400000，
     * 点选小时后 windowMs=3600000。记下窗口，用于小时口径的能量换算。
     */
    private void hookWindow(ClassLoader cl) {
        try {
            Class<?> viewModel = Class.forName("ja6", false, cl);
            for (Method method : viewModel.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if ("l".equals(method.getName()) && types.length == 3
                        && types[0] == long.class && types[1] == long.class) {
                    method.setAccessible(true);
                    hook(method)
                            .setId("window-capture")
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object start = chain.getArg(0);
                                    Object window = chain.getArg(1);
                                    if (start instanceof Long && window instanceof Long) {
                                        sWindowStart = (Long) start;
                                        sWindowMs = (Long) window;
                                    }
                                    return chain.proceed();
                                }
                            });
                    log(Log.INFO, TAG, "hooked ja6.l for window capture");
                    return;
                }
            }
            log(Log.WARN, TAG, "ja6.l(long,long,boolean) not found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook window failed", t);
        }
    }

    /**
     * 选小时后，标题/气泡会出现 “03:00 – 04:00”。直接从这里抓窗口，避免依赖加载路径。
     */
    private void hookTimeRange() {
        try {
            Class<?> textView = Class.forName("android.widget.TextView");
            for (Method method : textView.getDeclaredMethods()) {
                if (!"setText".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length == 1 && types[0] == CharSequence.class) {
                    method.setAccessible(true);
                    hook(method)
                            .setId("time-range-capture")
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object arg = chain.getArg(0);
                                    if (arg instanceof CharSequence) {
                                        captureTimeRange(arg.toString());
                                    }
                                    return chain.proceed();
                                }
                            });
                    return;
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook time range failed", t);
        }
    }

    private static void captureTimeRange(String text) {
        if (text == null || !text.contains("耗电排行")) {
            return;
        }
        Matcher m = TIME_RANGE.matcher(text);
        if (!m.find()) {
            // 切回 24 小时：标题只剩“耗电排行”，恢复全天窗口。
            sWindowStart = 0L;
            sWindowMs = DAY_MS;
            return;
        }
        try {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int h2 = Integer.parseInt(m.group(3));
            int min2 = Integer.parseInt(m.group(4));
            if (h > 23 || h2 > 23 || min > 59 || min2 > 59) {
                return;
            }
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, h);
            cal.set(java.util.Calendar.MINUTE, min);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long start = cal.getTimeInMillis();
            if (start > System.currentTimeMillis() + 60000L) {
                start -= DAY_MS;
            }
            sWindowStart = start;
            sWindowMs = HOUR_MS;
        } catch (Throwable ignored) {
            // 不是时间范围就不动
        }
    }

    /**
     * mo1.e(start, span, z) 是真正按时间窗查 DB 并组装列表的入口（ja6 会传 start+10s）。
     * 记下窗口，小时视图下用它做“该小时能量 ÷ 小时数”的换算。
     */
    private void hookModelLoad(ClassLoader cl) {
        try {
            Class<?> model = Class.forName("mo1", false, cl);
            for (Method method : model.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if ("e".equals(method.getName()) && types.length == 3
                        && types[0] == long.class && types[1] == long.class) {
                    method.setAccessible(true);
                    hook(method)
                            .setId("window-capture-model")
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(new Hooker() {
                                @Override
                                public Object intercept(Chain chain) throws Throwable {
                                    Object start = chain.getArg(0);
                                    Object span = chain.getArg(1);
                                    if (start instanceof Long && span instanceof Long) {
                                        long s = (Long) start;
                                        long w = (Long) span;
                                        // ja6 查询起点是 start+10s，这里对齐回真实窗口起点。
                                        sWindowStart = s > 10000L ? s - 10000L : s;
                                        sWindowMs = w;
                                    }
                                    return chain.proceed();
                                }
                            });
                    log(Log.INFO, TAG, "hooked mo1.e for window capture");
                    return;
                }
            }
            log(Log.WARN, TAG, "mo1.e(long,long,boolean) not found");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook model load failed", t);
        }
    }

    private static Method findBindMethod(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if ("onBindViewHolder".equals(method.getName())
                    && method.getParameterTypes().length == 2) {
                return method;
            }
        }
        return null;
    }

    private static void decorate(Object adapter, Object holder, Object position) throws Throwable {
        if (adapter == null || holder == null || !(position instanceof Integer)) {
            return;
        }
        int index = (Integer) position;

        Object rawList = field(adapter.getClass(), "e").get(adapter);
        if (!(rawList instanceof List)) {
            return;
        }
        List<?> items = (List<?>) rawList;
        if (index < 0 || index >= items.size()) {
            return;
        }
        Object item = items.get(index);
        if (item == null) {
            return;
        }

        Object consumeInfo = field(item.getClass(), "k").get(item);
        Object usageState = field(item.getClass(), "g").get(item);
        if (consumeInfo == null) {
            return;
        }
        long rawTotalPower = field(consumeInfo.getClass(), "d").getLong(consumeInfo);
        if (rawTotalPower <= 0L) {
            return;
        }

        Object textViewObj = field(holder.getClass(), "d").get(holder);
        if (!(textViewObj instanceof TextView)) {
            return;
        }
        TextView percentView = (TextView) textViewObj;
        CharSequence current = percentView.getText();
        if (current == null) {
            return;
        }
        String percent = current.toString();
        if (!percent.matches("[0-9]{1,3}%")) {
            return;
        }
        int percentValue = Integer.parseInt(percent.substring(0, percent.length() - 1));
        if (percentValue <= 3) {
            // 只给占比 >3% 的 App 标功耗
            return;
        }

        long itemStart = 0L;
        long itemEnd = 0L;
        try {
            Object startObj = field(item.getClass(), "i").get(item);
            Object endObj = field(item.getClass(), "j").get(item);
            if (startObj instanceof Number) {
                itemStart = ((Number) startObj).longValue();
            }
            if (endObj instanceof Number) {
                itemEnd = ((Number) endObj).longValue();
            }
        } catch (Throwable ignored) {
            // 没有 i/j 的列表按窗口判断
        }
        long itemSpan = itemEnd > itemStart ? itemEnd - itemStart : 0L;
        boolean sevenDayList = itemSpan > 2L * DAY_MS;

        String pkgName = null;
        try {
            Object pkgObj = field(item.getClass(), "c").get(item);
            if (pkgObj instanceof String) {
                pkgName = (String) pkgObj;
            }
        } catch (Throwable ignored) {
            // 不是字符串就不处理
        }
        if (pkgName != null && (pkgName.startsWith("hw_")
                || "display".equals(pkgName)
                || "other_service_aggregate".equals(pkgName))) {
            // 硬件/合并项：不标 App 功耗，避免和屏幕重复计算
            return;
        }

        double share = sharePercent(items, rawTotalPower);
        if (share < 1.0d) {
            return;
        }

        long windowMs = sWindowMs;
        if (!sevenDayList && windowMs > 0L && windowMs <= 2L * HOUR_MS) {
            if (usageState == null) {
                return;
            }
            long windowStart = sWindowStart;
            if (itemStart > 0L && itemSpan > 0L && itemSpan <= 2L * DAY_MS) {
                long dayStart = itemStart - Math.floorMod(itemStart, DAY_MS);
                long timeOfDay = Math.floorMod(sWindowStart, DAY_MS);
                windowStart = dayStart + timeOfDay;
            }
            long fgPower = getLong(usageState, "getFgPower");
            long bgPower = getLong(usageState, "getBgPower");
            double screenWatts = 0d;
            try {
                screenWatts = windowScreenWatts(percentView.getContext(), windowStart, windowMs);
            } catch (Throwable t2) {
                Log.e(TAG, "hour screen power failed", t2);
            }
            double fgWatts = fgPower * 3.6d / (double) windowMs + screenWatts;
            double bgWatts = bgPower * 3.6d / (double) windowMs;
            setLabeledText(percentView, percent, fgWatts, bgWatts, 2, 0.01d, 0L, 0L);
            return;
        }

        if (usageState == null) {
            return;
        }
        long fgPower = getLong(usageState, "getFgPower");
        long fgTimeMs = getLong(usageState, "getFgTime");
        long bgPower = getLong(usageState, "getBgPower");
        long bgTimeMs = getLong(usageState, "getBgTime");
        if (fgPower <= 0L && bgPower <= 0L) {
            return;
        }

        double fgWatts = 0d;
        if (fgPower > 0L && fgTimeMs > 0L) {
            fgWatts = fgPower * 3.6d / (double) fgTimeMs;
            double t = fgTimeMs / 1000.0d;
            fgWatts = (fgWatts * t + priorWatts(items) * SHRINK_SECONDS) / (t + SHRINK_SECONDS);
        }
        double bgWatts = 0d;
        if (bgPower > 0L && bgTimeMs >= 120000L) {
            bgWatts = bgPower * 3.6d / (double) bgTimeMs;
            double bt = bgTimeMs / 1000.0d;
            bgWatts = (bgWatts * bt + priorBgWatts(items) * 300d) / (bt + 300d);
            if (bgWatts > 10d) {
                bgWatts = 0d;
            }
        }

        double screenWatts = 0d;
        if (sevenDayList && itemStart > 0L && itemSpan > 0L) {
            try {
                screenWatts = windowScreenOnWatts(percentView.getContext(), itemStart, itemSpan);
            } catch (Throwable t4) {
                Log.e(TAG, "seven day screen power failed", t4);
            }
        } else if (!sevenDayList && pkgName != null
                && (itemSpan == 0L || itemSpan <= 2L * DAY_MS)) {
            long screenDay = itemStart;
            if (screenDay <= 0L) {
                long now = System.currentTimeMillis();
                screenDay = now - Math.floorMod(now, DAY_MS);
            }
            try {
                screenWatts = appScreenWatts(percentView.getContext(), screenDay, pkgName);
            } catch (Throwable t3) {
                Log.e(TAG, "screen power failed", t3);
            }
        }

        double foreground = fgWatts + screenWatts;
        if (!Double.isFinite(foreground) || foreground < 0d || foreground > 60d) {
            foreground = 0d;
        }
        if (!Double.isFinite(bgWatts) || bgWatts < 0d || bgWatts > 60d) {
            bgWatts = 0d;
        }
        setLabeledText(percentView, percent, foreground, bgWatts, 1, 0.05d, fgTimeMs, bgTimeMs);
    }

    /** 行内明确写出“前台/后台”，低于阈值的分量不显示。 */
    private static void setLabeledText(TextView view, String percent,
                                       double foreground, double background,
                                       int decimals, double min,
                                       long fgTimeMs, long bgTimeMs) {
        String fmt = decimals == 2 ? "%.2f" : "%.1f";
        StringBuilder sb = new StringBuilder();
        if (foreground >= min) {
            sb.append("前台").append(String.format(Locale.US, fmt, foreground)).append('W');
        }
        if (background >= min) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("后台").append(String.format(Locale.US, fmt, background)).append('W');
        }
        // 占比放最后：前台2.5W 后台1.7W · 31%
        view.setText(sb.length() == 0 ? percent : sb + " · " + percent);
    }

    private static double sharePercent(List<?> items, long raw) {
        synchronized (CACHE_LOCK) {
            refreshListStats(items);
            return sTotalRaw > 0d ? raw * 100.0d / sTotalRaw : 100d;
        }
    }

    private static double median(java.util.List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        java.util.ArrayList<Double> copy = new java.util.ArrayList<>(values);
        java.util.Collections.sort(copy);
        int size = copy.size();
        return size % 2 == 1
                ? copy.get(size / 2)
                : (copy.get(size / 2 - 1) + copy.get(size / 2)) / 2d;
    }

    private static double priorWatts(List<?> items) {
        synchronized (CACHE_LOCK) {
            refreshListStats(items);
            return sPriorWatts > 0d ? sPriorWatts : 1.2d;
        }
    }

    private static double priorBgWatts(List<?> items) {
        synchronized (CACHE_LOCK) {
            refreshListStats(items);
            return sPriorBgWatts > 0d ? sPriorBgWatts : 1.0d;
        }
    }

    /** 遍历一次列表，缓存“占比分母”和“整体前台/后台功率”（W）。 */
    private static void refreshListStats(List<?> items) {
        if (sTotalList == items) {
            return;
        }
        double total = 0d;
        java.util.ArrayList<Double> fgRates = new java.util.ArrayList<>();
        java.util.ArrayList<Double> bgRates = new java.util.ArrayList<>();
        for (Object item : items) {
            try {
                Object po1 = field(item.getClass(), "k").get(item);
                long raw = field(po1.getClass(), "d").getLong(po1);
                if (raw >= 3600L) {
                    total += raw;
                }
                Object usageState = field(item.getClass(), "g").get(item);
                if (usageState != null) {
                    long fgPower = getLong(usageState, "getFgPower");
                    long fgTimeMs = getLong(usageState, "getFgTime");
                    if (fgPower > 0L && fgTimeMs >= 60000L) {
                        fgRates.add(fgPower * 3.6d / (double) fgTimeMs);
                    }
                    long bgPower = getLong(usageState, "getBgPower");
                    long bgTimeMs = getLong(usageState, "getBgTime");
                    if (bgPower > 0L && bgTimeMs >= 120000L) {
                        bgRates.add(bgPower * 3.6d / (double) bgTimeMs);
                    }
                }
            } catch (Throwable ignored) {
                // 跳过坏条目
            }
        }
        sTotalList = items;
        sTotalRaw = total;
        sPriorWatts = median(fgRates);
        sPriorBgWatts = median(bgRates);
    }

    /** 多天窗口：屏幕能量 ÷ 亮屏时长，得到亮屏期间平均屏幕功率。 */
    private static double windowScreenOnWatts(Context context, long start, long span) {
        if (context == null || start <= 0L || span <= 0L) {
            return 0d;
        }
        String key = "scr|" + start + "|" + span;
        synchronized (CACHE_LOCK) {
            Double cached = APP_SCREEN.get(key);
            if (cached != null) {
                return cached;
            }
        }
        double energy = 0d;
        long onMs = 0L;
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(HISTORY_URI,
                new String[]{"backpower", "forepower"},
                "name=? AND date>=? AND date<?",
                new String[]{"display", Long.toString(start), Long.toString(start + span)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    energy += cursor.getDouble(0) + cursor.getDouble(1);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "query window display failed", t);
        }
        try (Cursor cursor = resolver.query(SCREEN_ON_URI,
                new String[]{"ontime", "offtime"},
                "ontime<? AND offtime>?",
                new String[]{Long.toString(start + span), Long.toString(start)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long on = Math.max(cursor.getLong(0), start);
                    long off = Math.min(cursor.getLong(1), start + span);
                    if (off > on) {
                        onMs += off - on;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "query window screen on failed", t);
        }
        double value = onMs > 0L ? energy * 3.6d / (double) onMs : 0d;
        if (!Double.isFinite(value) || value < 0d || value > 6d) {
            value = value > 6d ? 6d : 0d;
        }
        synchronized (CACHE_LOCK) {
            APP_SCREEN.put(key, value);
        }
        return value;
    }

    /** 指定窗口内 display 能量 → 平均瓦数（µWh 口径）。 */
    private static double windowScreenWatts(Context context, long start, long windowMs) {
        if (context == null || start <= 0L || windowMs <= 0L) {
            return 0d;
        }
        String key = "win|" + start + "|" + windowMs;
        synchronized (CACHE_LOCK) {
            Double cached = APP_SCREEN.get(key);
            if (cached != null) {
                return cached;
            }
        }
        double energy = 0d;
        try (Cursor cursor = context.getContentResolver().query(HISTORY_URI,
                new String[]{"backpower", "forepower"},
                "name=? AND date>=? AND date<?",
                new String[]{"display", Long.toString(start), Long.toString(start + windowMs)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    energy += cursor.getDouble(0) + cursor.getDouble(1);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "query window display failed", t);
        }
        double value = energy * 3.6d / (double) windowMs;
        if (!Double.isFinite(value) || value < 0d || value > 20d) {
            value = 0d;
        }
        synchronized (CACHE_LOCK) {
            APP_SCREEN.put(key, value);
        }
        return value;
    }

    private static double appScreenWatts(Context context, long dayStart, String pkg) {
        if (context == null || dayStart <= 0L || pkg == null || pkg.isEmpty()) {
            return 0d;
        }
        String key = dayStart + "|" + pkg;
        synchronized (CACHE_LOCK) {
            Double cached = APP_SCREEN.get(key);
            if (cached != null) {
                return cached;
            }
        }

        DayScreen day = dayScreen(context, dayStart);
        double value = day.averageWatts;
        if (day.hourWatts != null) {
            try (Cursor cursor = context.getContentResolver().query(HISTORY_URI,
                    new String[]{"date", "forepower"},
                    "name=? AND date>=? AND date<?",
                    new String[]{pkg, Long.toString(dayStart), Long.toString(dayStart + DAY_MS)},
                    null)) {
                double weight = 0d;
                double weighted = 0d;
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        long date = cursor.getLong(0);
                        double energy = Math.max(0d, cursor.getDouble(1));
                        int hour = (int) ((date - dayStart) / HOUR_MS);
                        if (hour < 0 || hour >= HOURS || energy <= 0d) {
                            continue;
                        }
                        if (day.screenOnMs == null || day.screenOnMs[hour] <= 0L
                                || day.hourWatts[hour] < 0d) {
                            continue;
                        }
                        weight += energy;
                        weighted += energy * day.hourWatts[hour];
                    }
                }
                value = weight > 0d ? weighted / weight : 0d;
            } catch (Throwable t) {
                Log.e(TAG, "query app hours failed", t);
            }
        }

        if (!Double.isFinite(value) || value < 0d || value > 6d) {
            value = value > 6d ? 6d : 0d;
        }
        synchronized (CACHE_LOCK) {
            APP_SCREEN.put(key, value);
        }
        return value;
    }

    private static DayScreen dayScreen(Context context, long dayStart) {
        synchronized (CACHE_LOCK) {
            DayScreen cached = DAY_SCREEN.get(dayStart);
            if (cached != null) {
                return cached;
            }
        }

        long dayEnd = dayStart + DAY_MS;
        double[] energy = new double[HOURS];
        long[] onMs = new long[HOURS];
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(HISTORY_URI,
                new String[]{"date", "backpower", "forepower"},
                "name=? AND date>=? AND date<?",
                new String[]{"display", Long.toString(dayStart), Long.toString(dayEnd)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int hour = (int) ((cursor.getLong(0) - dayStart) / HOUR_MS);
                    if (hour >= 0 && hour < HOURS) {
                        energy[hour] += cursor.getDouble(1) + cursor.getDouble(2);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "query display power failed", t);
        }

        try (Cursor cursor = resolver.query(SCREEN_ON_URI,
                new String[]{"ontime", "offtime"},
                "ontime<? AND offtime>?",
                new String[]{Long.toString(dayEnd), Long.toString(dayStart)},
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long on = Math.max(cursor.getLong(0), dayStart);
                    long off = Math.min(cursor.getLong(1), dayEnd);
                    while (on < off) {
                        int hour = (int) ((on - dayStart) / HOUR_MS);
                        if (hour < 0 || hour >= HOURS) {
                            break;
                        }
                        long hourEnd = dayStart + (hour + 1L) * HOUR_MS;
                        long sliceEnd = Math.min(off, hourEnd);
                        onMs[hour] += sliceEnd - on;
                        on = sliceEnd;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "query screen on time failed", t);
        }

        DayScreen day = new DayScreen();
        double totalEnergy = 0d;
        long totalOn = 0L;
        day.hourWatts = new double[HOURS];
        day.screenOnMs = onMs;
        for (int i = 0; i < HOURS; i++) {
            day.hourWatts[i] = -1d;
            if (onMs[i] <= 0L) {
                continue;
            }
            double watt = energy[i] * 3.6d / (double) onMs[i];
            // 单小时算出来 >6W 视为该小时数据异常，整点剔除，不用它参与平均
            if (!Double.isFinite(watt) || watt < 0d || watt > 6d) {
                continue;
            }
            day.hourWatts[i] = watt;
            totalEnergy += energy[i];
            totalOn += onMs[i];
        }
        day.averageWatts = totalOn > 0L ? totalEnergy * 3.6d / (double) totalOn : 0d;
        synchronized (CACHE_LOCK) {
            DAY_SCREEN.put(dayStart, day);
        }
        return day;
    }

    private static final class DayScreen {
        double averageWatts;
        double[] hourWatts;
        long[] screenOnMs;
    }

    private static boolean isSevenDaysTab(Object adapter) {
        try {
            Object fragment = field(adapter.getClass(), "b").get(adapter);
            if (fragment == null) {
                return false;
            }
            Object tab = field(fragment.getClass(), "f").get(fragment);
            return tab instanceof Enum && "SEVEN_DAYS".equals(((Enum<?>) tab).name());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long getLong(Object target, String getter) throws Exception {
        Object value = target.getClass().getMethod(getter).invoke(target);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Field field(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("field " + name + " not found on " + clazz.getName());
    }
}
