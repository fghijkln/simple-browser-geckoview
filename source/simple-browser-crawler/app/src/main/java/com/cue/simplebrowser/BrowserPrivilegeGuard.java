package com.cue.simplebrowser;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.Application;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Startup policy for privileges the browser does not need. This inspects only this process and
 * this package; it does not test whether the device is rooted or enumerate other applications.
 */
final class BrowserPrivilegeGuard {
    private static final int ROOT_UID = 0;
    private static final int SHELL_UID = 2000;
    private static final int UID_PER_USER_RANGE = 100000;
    private static final String ISOLATED_CONTENT_PROCESS_PREFIX = ":isolatedTab_disable_art_image_";
    private static final String DYNAMIC_RECEIVER_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";

    private static final Set<String> EXPECTED_SYSTEM_PERMISSIONS = immutableSet(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED");

    private static final Set<String> APPROVED_DANGEROUS_PERMISSIONS = immutableSet(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION");

    private static final Set<String> FORBIDDEN_SPECIAL_PERMISSION_NAMES = immutableSet(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.ACCESS_NOTIFICATION_POLICY",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE");

    private BrowserPrivilegeGuard() {}

    static void verifyOrThrow(Context suppliedContext) {
        if (suppliedContext == null) throw blocked("application context is unavailable");
        Context context = suppliedContext.getApplicationContext();
        if (context == null) throw blocked("application context is unavailable");

        final int uid = Process.myUid();
        if (uid == ROOT_UID || uid == SHELL_UID
                || (uid > SHELL_UID && uid % UID_PER_USER_RANGE == SHELL_UID)) {
            throw blocked("this app process is running as root UID 0 or shell UID 2000");
        }
        verifyEffectiveCapabilities();

        // GeckoView's declared isolatedTab services intentionally receive an isolated UID and no
        // app-granted permissions. They cannot answer package-scoped AppOps/DPM queries as the app.
        // Accept only the exact GeckoView 157 process family verified in its AAR manifest.
        if (Process.isIsolated()) {
            String processName = Application.getProcessName();
            String expectedPrefix = context.getPackageName() + ISOLATED_CONTENT_PROCESS_PREFIX;
            if (processName == null || !processName.startsWith(expectedPrefix)) {
                throw blocked("an unexpected isolated process attempted to run app code");
            }
            return;
        }

        verifyRequestedPermissions(context);
        verifySpecialAccess(context, uid);
        verifyDeviceManagementState(context);
        verifyEnabledAccessibilityServices(context);
        verifyEnabledNotificationListeners(context);
    }

    private static void verifyRequestedPermissions(Context context) {
        PackageManager packageManager = context.getPackageManager();
        final PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Cannot inspect this app's requested permissions", error);
        }
        if (packageInfo.requestedPermissions == null) {
            throw new IllegalStateException("This app's requested permissions are unavailable");
        }

        Set<String> expected = new HashSet<>(EXPECTED_SYSTEM_PERMISSIONS);
        expected.addAll(APPROVED_DANGEROUS_PERMISSIONS);
        String dynamicReceiverPermission = context.getPackageName() + DYNAMIC_RECEIVER_SUFFIX;
        expected.add(dynamicReceiverPermission);

        Set<String> requested = new HashSet<>(Arrays.asList(packageInfo.requestedPermissions));
        if (!requested.equals(expected)) {
            Set<String> unexpected = new HashSet<>(requested);
            unexpected.removeAll(expected);
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(requested);
            throw blocked("requested permission allowlist mismatch; unexpected=" + unexpected
                    + ", missing=" + missing);
        }

        // Query actual dangerous grants. Any unapproved declaration was already rejected above;
        // these four are the only runtime-dangerous permissions the browser is allowed to hold.
        for (String permission : requested) {
            final PermissionInfo permissionInfo;
            try {
                permissionInfo = packageManager.getPermissionInfo(permission, 0);
            } catch (PackageManager.NameNotFoundException error) {
                // This foreground-service permission was added after the app's minSdk and may be
                // unknown to older Android releases. Its exact name remains allowlisted above.
                if ("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED".equals(permission)) continue;
                throw new IllegalStateException("Cannot inspect requested permission " + permission, error);
            }
            int protection = permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            if (protection == PermissionInfo.PROTECTION_DANGEROUS
                    && context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                    && !APPROVED_DANGEROUS_PERMISSIONS.contains(permission)) {
                throw blocked("unapproved dangerous permission is granted: " + permission);
            }
        }

        PermissionInfo dynamicPermission;
        try {
            dynamicPermission = packageManager.getPermissionInfo(dynamicReceiverPermission, 0);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("AndroidX receiver protection permission is missing", error);
        }
        int protection = dynamicPermission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
        if (protection != PermissionInfo.PROTECTION_SIGNATURE) {
            throw blocked("AndroidX dynamic receiver permission is not signature protected");
        }

        for (String forbidden : FORBIDDEN_SPECIAL_PERMISSION_NAMES) {
            if (requested.contains(forbidden)) {
                throw blocked("unapproved special permission is declared: " + forbidden);
            }
        }
    }

    private static void verifySpecialAccess(Context context, int uid) {
        String packageName = context.getPackageName();
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) throw new IllegalStateException("AppOpsManager is unavailable");

        rejectAllowedPermissionAppOp(appOps, "android.permission.SYSTEM_ALERT_WINDOW", uid, packageName);
        rejectAllowedPermissionAppOp(appOps, "android.permission.WRITE_SETTINGS", uid, packageName);
        rejectAllowedPermissionAppOp(appOps, "android.permission.PACKAGE_USAGE_STATS", uid, packageName);
        if (Build.VERSION.SDK_INT >= 30) {
            rejectAllowedPermissionAppOp(appOps, "android.permission.MANAGE_EXTERNAL_STORAGE", uid, packageName);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            rejectAllowedPermissionAppOp(appOps, "android.permission.REQUEST_INSTALL_PACKAGES", uid, packageName);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            rejectAllowedPermissionAppOp(appOps, "android.permission.SCHEDULE_EXACT_ALARM", uid, packageName);
        }

        if (Settings.canDrawOverlays(context)) throw blocked("overlay access is enabled");
        if (Settings.System.canWrite(context)) throw blocked("write-settings access is enabled");
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            throw blocked("all-files access is enabled");
        }
        if (context.getPackageManager().canRequestPackageInstalls()) {
            throw blocked("install-unknown-apps access is enabled");
        }

        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            throw new IllegalStateException("NotificationManager is unavailable");
        }
        if (notificationManager.isNotificationPolicyAccessGranted()) {
            throw blocked("notification-policy access is enabled");
        }

        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
            if (alarmManager == null) throw new IllegalStateException("AlarmManager is unavailable");
            if (alarmManager.canScheduleExactAlarms()) {
                throw blocked("exact-alarm access is enabled");
            }
        }
    }

    private static void rejectAllowedAppOp(AppOpsManager appOps, String operation,
                                           int uid, String packageName) {
        int mode = appOps.checkOpNoThrow(operation, uid, packageName);
        if (mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND) {
            throw blocked("unneeded special AppOp is allowed: " + operation);
        }
    }

    private static void rejectAllowedPermissionAppOp(AppOpsManager appOps, String permission,
                                                     int uid, String packageName) {
        String operation = AppOpsManager.permissionToOp(permission);
        if (operation == null) {
            throw new IllegalStateException("Cannot map special permission to AppOp: " + permission);
        }
        rejectAllowedAppOp(appOps, operation, uid, packageName);
    }

    private static void verifyDeviceManagementState(Context context) {
        String packageName = context.getPackageName();
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm == null) throw new IllegalStateException("DevicePolicyManager is unavailable");
        if (dpm.isDeviceOwnerApp(packageName)) throw blocked("this app is the device owner");
        if (dpm.isProfileOwnerApp(packageName)) throw blocked("this app is a profile owner");

        // Restrict the query to this package rather than enumerating device administrators globally.
        Intent intent = new Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
                .setPackage(packageName);
        List<ResolveInfo> receivers = context.getPackageManager().queryBroadcastReceivers(intent, 0);
        if (receivers == null) throw new IllegalStateException("Cannot inspect this app's admin receivers");
        for (ResolveInfo resolveInfo : receivers) {
            ActivityInfo info = resolveInfo.activityInfo;
            if (info == null || info.name == null) {
                throw new IllegalStateException("This app's device-admin receiver metadata is unreadable");
            }
            ComponentName component = new ComponentName(packageName, info.name);
            if (dpm.isAdminActive(component)) throw blocked("this app has an active device administrator");
        }
    }

    private static void verifyEnabledAccessibilityServices(Context context) {
        String packageName = context.getPackageName();
        Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(packageName);
        List<ResolveInfo> services = context.getPackageManager().queryIntentServices(intent, 0);
        if (services == null) {
            throw new IllegalStateException("Cannot inspect this app's accessibility services");
        }
        Set<ComponentName> declaredAccessibilityServices = new HashSet<>();
        for (ResolveInfo resolveInfo : services) {
            ServiceInfo info = resolveInfo.serviceInfo;
            if (info == null || info.name == null) {
                throw new IllegalStateException("This app's accessibility-service metadata is unreadable");
            }
            if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(info.permission)) {
                declaredAccessibilityServices.add(new ComponentName(packageName, info.name));
            }
        }
        if (declaredAccessibilityServices.isEmpty()) return;
        if (isAnyOwnComponentEnabled(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                declaredAccessibilityServices)) {
            throw blocked("this app has an enabled AccessibilityService");
        }
    }

    private static void verifyEnabledNotificationListeners(Context context) {
        String packageName = context.getPackageName();
        Intent intent = new Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(packageName);
        List<ResolveInfo> services = context.getPackageManager().queryIntentServices(intent, 0);
        if (services == null) {
            throw new IllegalStateException("Cannot inspect this app's notification listeners");
        }
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            throw new IllegalStateException("NotificationManager is unavailable");
        }
        for (ResolveInfo resolveInfo : services) {
            ServiceInfo info = resolveInfo.serviceInfo;
            if (info == null || info.name == null) {
                throw new IllegalStateException("This app's notification-listener metadata is unreadable");
            }
            if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(info.permission)) {
                ComponentName component = new ComponentName(packageName, info.name);
                if (notificationManager.isNotificationListenerAccessGranted(component)) {
                    throw blocked("this app has an enabled NotificationListenerService");
                }
            }
        }
    }

    private static boolean isAnyOwnComponentEnabled(Context context, String setting,
                                                     Set<ComponentName> ownComponents) {
        String value = Settings.Secure.getString(context.getContentResolver(), setting);
        if (value == null || value.trim().isEmpty()) return false;
        String packageName = context.getPackageName();
        for (String flattened : value.split(":")) {
            if (flattened == null || !flattened.startsWith(packageName + "/")) continue;
            ComponentName component = ComponentName.unflattenFromString(flattened);
            if (component == null) {
                throw new IllegalStateException("This app's enabled-service setting is malformed");
            }
            if (ownComponents.contains(component)) return true;
        }
        return false;
    }

    private static void verifyEffectiveCapabilities() {
        Map<String, String> capabilityMasks = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/self/status"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String field : new String[] {"CapEff", "CapPrm", "CapInh", "CapAmb"}) {
                    if (line.startsWith(field + ":")) {
                        if (capabilityMasks.containsKey(field)) {
                            throw new IllegalStateException("duplicate " + field + " status field");
                        }
                        capabilityMasks.put(field, line.substring(field.length() + 1).trim());
                        break;
                    }
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect this process's effective capabilities", error);
        }
        for (String field : new String[] {"CapEff", "CapPrm", "CapInh", "CapAmb"}) {
            String value = capabilityMasks.get(field);
            if (value == null || !value.matches("[0-9a-fA-F]+")) {
                throw new IllegalStateException("This process's " + field + " capability mask is unreadable");
            }
            if (new BigInteger(value, 16).signum() != 0) {
                throw blocked("this process has non-zero Linux " + field + " capabilities: 0x" + value);
            }
        }
    }

    private static SecurityException blocked(String reason) {
        return new SecurityException("Privilege guard blocked startup: " + reason);
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
