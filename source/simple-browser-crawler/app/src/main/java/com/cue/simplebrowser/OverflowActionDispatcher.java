package com.cue.simplebrowser;

/** Dismisses the overflow menu before forwarding its supported actions. */
final class OverflowActionDispatcher {
    interface Host {
        void dismissOverflowMenu();

        void dispatchOtherAction(String actionId);
    }

    private OverflowActionDispatcher() { }

    static void dispatch(String actionId, Host host) {
        if (actionId == null || host == null) return;
        host.dismissOverflowMenu();
        host.dispatchOtherAction(actionId);
    }
}
