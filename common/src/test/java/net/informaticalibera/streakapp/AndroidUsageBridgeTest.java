package net.informaticalibera.streakapp;

import com.codename1.system.NativeLookup;
import com.codename1.testing.AbstractTest;
import java.util.List;
import net.informaticalibera.streakapp.GoalStore.InstalledApp;
import net.informaticalibera.streakapp.native_.AndroidUsageBridge;

public class AndroidUsageBridgeTest extends AbstractTest {
    @Override
    public boolean shouldExecuteOnEDT() {
        return false;
    }

    @Override
    public boolean runTest() {
        AndroidUsageBridge bridge =
                (AndroidUsageBridge)NativeLookup.create(AndroidUsageBridge.class);
        assertNotNull(bridge, "Android usage bridge should be registered");
        assertTrue(bridge.isSupported(), "Android usage bridge should be supported");

        String encoded = bridge.listLaunchableApps();
        assertNotNull(encoded, "Installed-app query should return JSON");
        List<InstalledApp> apps = new GoalStore().parseInstalledApps(encoded);
        assertTrue(!apps.isEmpty(), "At least one launchable app should be visible");
        for (int i = 0; i < apps.size(); i++) {
            InstalledApp app = apps.get(i);
            assertTrue(!"net.informaticalibera.streakapp".equals(app.packageName),
                    "StreakApp must not be offered as its own goal");
        }
        assertTrue(bridge.isPackageLaunchable(apps.get(0).packageName),
                "Apps returned by the picker query should be launchable");
        return true;
    }
}
