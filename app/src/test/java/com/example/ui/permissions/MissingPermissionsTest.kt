package com.example.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class MissingPermissionsTest {

    // ── Helper ──────────────────────────────────────────────────────
    private fun perm(hasOverlay: Boolean, shizukuPing: Boolean, shizukuGranted: Boolean,
                     hasPostNotifications: Boolean = true, batteryUnrestricted: Boolean = true) =
        PermissionSnapshot(hasOverlay, shizukuPing, shizukuGranted, hasPostNotifications, batteryUnrestricted)

    // ── Todos concedidos ────────────────────────────────────────────
    @Test fun `all permissions present - nothing missing`() {
        assertEquals(emptySet<Permission>(), missingPermissions(perm(true, true, true), 34))
    }

    // ── OVERLAY ─────────────────────────────────────────────────────
    @Test fun `overlay missing`() {
        assertEquals(setOf(Permission.OVERLAY), missingPermissions(perm(false, true, true), 34))
    }

    // ── SHIZUKU ─────────────────────────────────────────────────────
    @Test fun `shizuku ping true but grant missing`() {
        assertEquals(setOf(Permission.SHIZUKU), missingPermissions(perm(true, true, false), 34))
    }

    @Test fun `shizuku ping false - not requested even if grant missing`() {
        assertEquals(emptySet<Permission>(), missingPermissions(perm(true, false, false), 34))
    }

    // ── POST_NOTIFICATIONS ──────────────────────────────────────────
    @Test fun `post notifications missing on Tiramisu+`() {
        assertEquals(setOf(Permission.POST_NOTIFICATIONS),
            missingPermissions(perm(true, true, true, hasPostNotifications = false), 33))
    }

    @Test fun `post notifications not checked below 33`() {
        assertEquals(emptySet<Permission>(),
            missingPermissions(perm(true, true, true, hasPostNotifications = false), 32))
    }

    // ── BATTERY ─────────────────────────────────────────────────────
    @Test fun `battery missing on M+`() {
        assertEquals(setOf(Permission.BATTERY),
            missingPermissions(perm(true, true, true, batteryUnrestricted = false), 23))
    }

    @Test fun `battery not checked below M`() {
        assertEquals(emptySet<Permission>(),
            missingPermissions(perm(true, true, true, batteryUnrestricted = false), 22))
    }

    // ── Múltiples ───────────────────────────────────────────────────
    @Test fun `multiple permissions missing`() {
        assertEquals(
            setOf(Permission.OVERLAY, Permission.SHIZUKU, Permission.POST_NOTIFICATIONS, Permission.BATTERY),
            missingPermissions(perm(false, true, false, false, false), 33)
        )
    }

    @Test fun `shizuku not requested when ping false even if all others missing`() {
        assertEquals(
            setOf(Permission.OVERLAY, Permission.POST_NOTIFICATIONS, Permission.BATTERY),
            missingPermissions(perm(false, false, false, false, false), 33)
        )
    }
}
