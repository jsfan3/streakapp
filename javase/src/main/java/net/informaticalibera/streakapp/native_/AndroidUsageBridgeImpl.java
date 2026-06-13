package net.informaticalibera.streakapp.native_;

public class AndroidUsageBridgeImpl implements AndroidUsageBridge {
    public boolean launchPackage(String packageName) {
        return false;
    }

    public void openUsageAccessSettings() {
    }

    public boolean isPackageLaunchable(String packageName) {
        return true;
    }

    public long getForegroundMillis(String packageName, long fromMillis, long toMillis) {
        return 0L;
    }

    public String listLaunchableApps() {
        return "{\"apps\":["
                + "{\"name\":\"AnkiDroid\",\"package\":\"com.ichi2.anki\"},"
                + "{\"name\":\"Example Notes\",\"package\":\"com.example.notes\"},"
                + "{\"name\":\"Paper Reader\",\"package\":\"com.example.reader\"}"
                + "]}";
    }

    public boolean isUsageAccessGranted() {
        return true;
    }

    public boolean isSupported() {
        return true;
    }
}
