#!/usr/bin/env python3
"""Static regression for non-fatal privilege findings and visible startup disclosure flow."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
java = ROOT / "app/src/main/java/com/cue/simplebrowser"
guard = (java / "BrowserPrivilegeGuard.java").read_text(encoding="utf-8")
app = (java / "PrivilegeGuardApplication.java").read_text(encoding="utf-8")
main = (java / "MainActivity.java").read_text(encoding="utf-8")
zh = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
en = (ROOT / "app/src/main/res/values-en/strings.xml").read_text(encoding="utf-8")

assert "static List<String> inspect(Context suppliedContext)" in guard
assert "if (suppliedContext == null) throw new IllegalStateException" in guard
assert "if (context == null) throw new IllegalStateException" in guard
collect = guard[guard.index("private static void collect("):guard.index("private static void verifyRequestedPermissions(")]
assert "catch (RuntimeException | LinkageError error)" in collect
assert "findings.add(category + \": \"" in collect
assert "throw error" not in collect and "System.exit" not in collect
for check in (
    "verifyEffectiveCapabilities", "verifyRequestedPermissions", "verifySpecialAccess",
    "verifyDeviceManagementState", "verifyEnabledAccessibilityServices", "verifyEnabledNotificationListeners",
):
    assert check in guard, f"preserve other app-own health checks: {check}"
assert "EXPECTED_SYSTEM_PERMISSIONS" in guard and "APPROVED_DANGEROUS_PERMISSIONS" in guard

assert "refreshPrivilegeFindings(this)" in app
assert "privilegeFindings = BrowserPrivilegeGuard.inspect(context)" in app
assert "onActivityPreResumed" in app, "findings should refresh after Settings/system permission dialogs"

cold_start = main[main.index("protected void onCreate(Bundle savedInstanceState)"):
                  main.index("private void showPolicyDisclosure(")]
assert "savedInstanceState == null" in cold_start and "showPolicyDisclosure(true" in cold_start
show = main[main.index("private void showPolicyDisclosure("):main.index("@android.annotation.SuppressLint(\"ApplySharedPref\")\n    private void initializeBrowserUi")]
assert "getPrivilegeFindings()" in show and "privilege_findings_title" in show
assert "setPositiveButton(R.string.privilege_continue" in show
assert "setNegativeButton(R.string.privilege_exit" in show
assert "initializeBrowserUi(restoreState)" in show
assert "finishAndRemoveTask()" in show and "disclosure.show()" in show
assert "setCancelable(!startup)" in show and "setCanceledOnTouchOutside(false)" in show
assert "R.string.privilege_disclaimer_settings" in main, "allow re-opening the notice from Settings"

for term in ("USB/ADB", "robots.txt", "登录墙", "付费墙", "CAPTCHA",
             "法定责任", "授权状态", "回退", "不限", "ANR"):
    assert term in zh, f"Chinese notice omits material disclosure term: {term}"
for term in ("USB/ADB", "robots.txt", "login", "paywall", "CAPTCHA",
             "legal responsibility", "authorization status", "rollback", "Unlimited", "ANR"):
    assert term.lower() in en.lower(), f"English notice omits material disclosure term: {term}"

print("PASS: ordinary guard failures become app-local warning findings; fatal context corruption remains fatal")
print("PASS: other app-own permission/AppOp/capability/device state checks remain in place")
print("PASS: fresh launch visibly lists findings and requires Continue/Exit; Settings can reopen bilingual notice")
