package net.informaticalibera.streakapp;

import com.codename1.testing.AbstractTest;
import com.codename1.testing.TestUtils;
import com.codename1.ui.Display;
import java.util.ArrayList;
import java.util.List;
import net.informaticalibera.streakapp.GoalStore.InstalledApp;

public class SettingsFormTest extends AbstractTest {
    @Override
    public boolean shouldExecuteOnEDT() {
        return true;
    }

    @Override
    public boolean runTest() {
        StreakApp app = new StreakApp();
        app.init(null);

        GoalStore store = new GoalStore();
        List<InstalledApp> installed = defaultInstalledApps();
        store.reconcileInstalledApps(installed);
        assertTrue(store.containsApp("com.duolingo"),
                "The reconciled model should include installed default apps");

        app.showSettings();

        assertEqual("settingsForm", Display.getInstance().getCurrent().getName(),
                "Settings form should be displayed");
        assertNotNull(TestUtils.findByName("addAppButton"), "Add-app action should be available");
        assertNotNull(TestUtils.findByName("addManualButton"), "Add-activity action should be available");
        assertNotNull(TestUtils.findByName("goalRow.app.com.ichi2.anki"),
                "Default app goals should remain available");
        assertNotNull(TestUtils.findByName("goalRow.app.com.duolingo"),
                "All default app goals should be rendered");

        installed.remove(1);
        store.reconcileInstalledApps(installed);
        app.showSettings();
        assertNull(TestUtils.findByName("goalRow.app.com.duolingo"),
                "A default app that is not installed should disappear from settings");

        store.reconcileInstalledApps(defaultInstalledApps());
        return true;
    }

    private List<InstalledApp> defaultInstalledApps() {
        List<InstalledApp> installed = new ArrayList<InstalledApp>();
        installed.add(new InstalledApp("AnkiDroid", "com.ichi2.anki"));
        installed.add(new InstalledApp("Duolingo", "com.duolingo"));
        installed.add(new InstalledApp("Drops", "com.languagedrops.drops.international"));
        installed.add(new InstalledApp("Rosetta Stone", "air.com.rosettastone.mobile.CoursePlayer"));
        installed.add(new InstalledApp("Talkpal", "ai.talkpal"));
        installed.add(new InstalledApp("Quizlet", "com.quizlet.quizletandroid"));
        return installed;
    }
}
