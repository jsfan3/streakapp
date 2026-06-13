package net.informaticalibera.streakapp.native_;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;
import com.codename1.impl.android.AndroidNativeUtil;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AndroidUsageBridgeImpl implements AndroidUsageBridge {
    private static final String TAG = "StreakAppUsage";
    private static final long LOOKBACK_MILLIS = 24L * 60L * 60L * 1000L;

    public boolean launchPackage(String packageName) {
        Context context = getContext();
        if (context == null || packageName == null || packageName.length() == 0) {
            return false;
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    public void openUsageAccessSettings() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public boolean isPackageLaunchable(String packageName) {
        Context context = getContext();
        if (context == null || packageName == null || packageName.length() == 0) {
            return false;
        }
        PackageManager packageManager = context.getPackageManager();
        return packageManager.getLaunchIntentForPackage(packageName) != null;
    }

    public long getForegroundMillis(String packageName, long fromMillis, long toMillis) {
        Context context = getContext();
        if (context == null || packageName == null || packageName.length() == 0 || toMillis <= fromMillis) {
            return 0L;
        }
        if (!isUsageAccessGranted()) {
            return 0L;
        }

        UsageStatsManager usageStatsManager = (UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return 0L;
        }

        long queryStart = Math.max(0L, fromMillis - LOOKBACK_MILLIS);
        UsageEvents events = usageStatsManager.queryEvents(queryStart, toMillis);
        if (events == null) {
            return 0L;
        }

        UsageEvents.Event event = new UsageEvents.Event();
        Set<Integer> activeActivities = new HashSet<Integer>();
        long activeFrom = -1L;
        long total = 0L;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (!packageName.equals(event.getPackageName())) {
                continue;
            }
            int type = event.getEventType();
            long timestamp = event.getTimeStamp();
            int activityKey = activityKey(event);
            if (isForegroundStart(type)) {
                if (activeActivities.isEmpty()) {
                    activeFrom = Math.max(timestamp, fromMillis);
                }
                activeActivities.add(Integer.valueOf(activityKey));
            } else if (isForegroundEnd(type) && activeActivities.remove(Integer.valueOf(activityKey))
                    && activeActivities.isEmpty() && activeFrom >= 0L) {
                long end = Math.min(timestamp, toMillis);
                if (end > activeFrom) {
                    total += end - activeFrom;
                }
                activeFrom = -1L;
            }
        }
        if (activeFrom >= 0L && toMillis > activeFrom) {
            total += toMillis - activeFrom;
        }
        return Math.max(0L, total);
    }

    public String listLaunchableApps() {
        Context context = getContext();
        JSONObject root = new JSONObject();
        JSONArray encodedApps = new JSONArray();
        if (context == null) {
            try {
                root.put("apps", encodedApps);
            } catch (JSONException ignored) {
            }
            return root.toString();
        }

        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        Map<String, String> unique = new LinkedHashMap<String, String>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (context.getPackageName().equals(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(packageManager);
            String name = label == null ? packageName : label.toString().trim();
            if (name.length() == 0) {
                name = packageName;
            }
            if (!unique.containsKey(packageName)) {
                unique.put(packageName, name);
            }
        }

        List<Map.Entry<String, String>> apps =
                new ArrayList<Map.Entry<String, String>>(unique.entrySet());
        Collections.sort(apps, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> left, Map.Entry<String, String> right) {
                int byName = left.getValue().compareToIgnoreCase(right.getValue());
                return byName != 0 ? byName : left.getKey().compareTo(right.getKey());
            }
        });
        for (Map.Entry<String, String> app : apps) {
            JSONObject encoded = new JSONObject();
            try {
                encoded.put("name", app.getValue());
                encoded.put("package", app.getKey());
                encodedApps.put(encoded);
            } catch (JSONException ex) {
                Log.e(TAG, "Unable to encode installed app", ex);
            }
        }
        try {
            root.put("apps", encodedApps);
        } catch (JSONException ex) {
            Log.e(TAG, "Unable to encode installed app list", ex);
        }
        return root.toString();
    }

    public boolean isUsageAccessGranted() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        try {
            AppOpsManager appOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            Log.e(TAG, "Unable to check usage access", t);
            return false;
        }
    }

    public boolean isSupported() {
        return true;
    }

    private boolean isForegroundStart(int eventType) {
        return eventType == UsageEvents.Event.ACTIVITY_RESUMED
                || eventType == UsageEvents.Event.MOVE_TO_FOREGROUND;
    }

    private boolean isForegroundEnd(int eventType) {
        return eventType == UsageEvents.Event.ACTIVITY_PAUSED
                || eventType == UsageEvents.Event.ACTIVITY_STOPPED
                || eventType == UsageEvents.Event.MOVE_TO_BACKGROUND;
    }

    private int activityKey(UsageEvents.Event event) {
        int instanceId = eventInstanceId(event);
        if (instanceId != 0) {
            return instanceId;
        }
        String className = event.getClassName();
        return className == null ? 0 : className.hashCode();
    }

    private int eventInstanceId(UsageEvents.Event event) {
        try {
            Method method = UsageEvents.Event.class.getMethod("getInstanceId");
            Object value = method.invoke(event);
            return value instanceof Integer ? ((Integer)value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Context getContext() {
        try {
            if (AndroidNativeUtil.getActivity() != null) {
                return AndroidNativeUtil.getActivity();
            }
        } catch (Throwable ignored) {
        }
        try {
            return AndroidNativeUtil.getContext();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
