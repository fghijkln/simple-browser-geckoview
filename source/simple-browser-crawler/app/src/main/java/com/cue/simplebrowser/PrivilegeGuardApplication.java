package com.cue.simplebrowser;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Runs the privilege guard before any Activity or Service in each app process starts. */
public final class PrivilegeGuardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        BrowserPrivilegeGuard.verifyOrThrow(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityPreResumed(Activity activity) {
                // Catch grants or special-access changes made while the process was suspended.
                BrowserPrivilegeGuard.verifyOrThrow(activity);
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
