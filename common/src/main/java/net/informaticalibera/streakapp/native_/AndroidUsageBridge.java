package net.informaticalibera.streakapp.native_;

import com.codename1.system.NativeInterface;

public interface AndroidUsageBridge extends NativeInterface {
    boolean isUsageAccessGranted();

    void openUsageAccessSettings();

    boolean isPackageLaunchable(String packageName);

    boolean launchPackage(String packageName);

    long getForegroundMillis(String packageName, long fromMillis, long toMillis);

    String listLaunchableApps();

    void startResumeMonitoring();

    long getResumeSequence();
}
