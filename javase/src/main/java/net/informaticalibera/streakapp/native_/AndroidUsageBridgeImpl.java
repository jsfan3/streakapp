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

    public boolean isUsageAccessGranted() {
        return true;
    }

    public boolean isSupported() {
        return true;
    }
}
