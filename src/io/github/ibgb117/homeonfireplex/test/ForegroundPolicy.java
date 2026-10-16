// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.ibgb117.homeonfireplex.test;

/** Fail-closed interpretation of a freshly sampled accessibility window. */
final class ForegroundPolicy {
    static AutostartSession.Surface classify(String target, String pkg, boolean visible,
            boolean focusedApplication, boolean homeEvidence, boolean knownLauncherActivity) {
        if (!visible || pkg.isEmpty()) return AutostartSession.Surface.UNKNOWN;
        if (!target.isEmpty() && pkg.equals(target)) {
            return focusedApplication ? AutostartSession.Surface.TARGET : AutostartSession.Surface.UNKNOWN;
        }
        if (pkg.equals("com.amazon.tv.launcher") || pkg.equals("com.amazon.tahoe")) {
            if (homeEvidence) {
                return focusedApplication ? AutostartSession.Surface.HOME : AutostartSession.Surface.UNKNOWN;
            }
            return knownLauncherActivity ? AutostartSession.Surface.OTHER : AutostartSession.Surface.UNKNOWN;
        }
        if (pkg.equals("android") || pkg.equals("com.android.systemui")) {
            return AutostartSession.Surface.UNKNOWN;
        }
        return AutostartSession.Surface.OTHER;
    }

    static boolean isHomeViewId(String id) {
        return id != null && id.startsWith("com.amazon.tv.launcher:id/nav-tab-movies_and_tv_home_tab");
    }
}
