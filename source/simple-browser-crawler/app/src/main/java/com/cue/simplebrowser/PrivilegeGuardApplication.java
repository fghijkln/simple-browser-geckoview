package com.cue.simplebrowser;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.util.Collections;
import java.util.List;

/** Runs the privilege guard before any Activity or Service in each app process starts. */
public final class PrivilegeGuardApplication extends Application {
    private volatile List<String> privilegeFindings = Collections.emptyList();

    public List<String> getPrivilegeFindings() {
        return privilegeFindings;
    }

    private void refreshPrivilegeFindings(android.content.Context context) {
        privilegeFindings = BrowserPrivilegeGuard.inspect(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        refreshPrivilegeFindings(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityPreResumed(Activity activity) {
                // Refresh app-local health findings after Settings or system permission dialogs.
                refreshPrivilegeFindings(activity);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}
