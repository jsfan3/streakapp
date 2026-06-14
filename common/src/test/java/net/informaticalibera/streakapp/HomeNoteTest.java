package net.informaticalibera.streakapp;

import com.codename1.testing.AbstractTest;
import com.codename1.testing.TestUtils;
import com.codename1.io.Util;
import com.codename1.ui.Component;
import com.codename1.ui.Display;
import com.codename1.ui.TextArea;
import com.codename1.components.SpanLabel;
import java.util.ArrayList;
import java.util.List;
import net.informaticalibera.streakapp.GoalStore.InstalledApp;

public class HomeNoteTest extends AbstractTest {
    private static final String ANKI_ID = "app.com.ichi2.anki";
    private static final String NOTE = "Review the difficult cards.\nThen repeat yesterday's errors.";

    @Override
    public boolean shouldExecuteOnEDT() {
        return false;
    }

    @Override
    public boolean runTest() {
        StreakApp app = new StreakApp();
        GoalStore store = new GoalStore();
        store.reconcileInstalledApps(installedApps());

        Display.getInstance().callSeriallyAndWait(() -> {
            app.init(null);
            app.showSettings();
            TextArea editor = (TextArea)TestUtils.findByName("goalNote." + ANKI_ID);
            editor.setText(NOTE);
            app.runApp();
        });

        Component note = waitForComponent("goalNoteHome." + ANKI_ID, 10000L);
        assertTrue(note instanceof SpanLabel, "Home notes should use a wrapping SpanLabel");
        assertEqual(NOTE, ((SpanLabel)note).getText(),
                "The complete multiline note should be displayed on the home card");

        Display.getInstance().callSeriallyAndWait(() -> {
            app.showSettings();
            TextArea editor = (TextArea)TestUtils.findByName("goalNote." + ANKI_ID);
            editor.setText("");
        });
        return true;
    }

    private Component waitForComponent(String name, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Component[] result = new Component[1];
        while (System.currentTimeMillis() < deadline) {
            Display.getInstance().callSeriallyAndWait(() -> result[0] = TestUtils.findByName(name));
            if (result[0] != null) {
                return result[0];
            }
            Util.sleep(100);
        }
        return null;
    }

    private List<InstalledApp> installedApps() {
        List<InstalledApp> installed = new ArrayList<InstalledApp>();
        installed.add(new InstalledApp("AnkiDroid", "com.ichi2.anki"));
        installed.add(new InstalledApp("Example Notes", "com.example.notes"));
        installed.add(new InstalledApp("Paper Reader", "com.example.reader"));
        return installed;
    }
}
