namespace net.informaticalibera.streakapp.native_{


public class AndroidUsageBridgeImpl : IAndroidUsageBridgeImpl {
    public bool launchPackage(String param) {
        return false;
    }

    public void openUsageAccessSettings() {
    }

    public bool isPackageLaunchable(String param) {
        return false;
    }

    public long getForegroundMillis(String param, long param1, long param2) {
        return 0;
    }

    public bool isUsageAccessGranted() {
        return false;
    }

    public bool isSupported() {
        return false;
    }

}
}
