package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.SmartBoardConfig
import com.example.model.SmartBoardPreferences
import com.example.viewmodel.BoardScreenMode
import com.example.viewmodel.SmartBoardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SmartBoard 86 Locker", appName)
    }

    @Test
    fun `smartboard preferences save and load config`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SmartBoardPreferences(context)
        val initialConfig = prefs.loadConfig()
        assertTrue(initialConfig.keepScreenAwake)
        assertEquals("1234", initialConfig.adminPin)
        assertEquals(45, initialConfig.defaultSessionMinutes)

        val updatedConfig = initialConfig.copy(
            botToken = "123456:ABC-DEF",
            botUsername = "my_smartboard_bot",
            boardId = "ROOM-101"
        )
        prefs.saveConfig(updatedConfig)

        val reloaded = prefs.loadConfig()
        assertEquals("123456:ABC-DEF", reloaded.botToken)
        assertEquals("my_smartboard_bot", reloaded.botUsername)
        assertEquals("ROOM-101", reloaded.boardId)
    }

    @Test
    fun `smartboard viewmodel starts locked and unlocks with session`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = SmartBoardViewModel(app)

        val initial = viewModel.uiState.value
        assertTrue("Initially board must be locked", initial.isLocked)
        assertEquals(BoardScreenMode.STANDBY_LOCK, initial.currentMode)

        // Start session
        viewModel.startLessonSession("O'qituvchi Test", 45)
        val unlocked = viewModel.uiState.value
        assertFalse("Board must be unlocked after starting session", unlocked.isLocked)
        assertEquals(BoardScreenMode.UNLOCKED_DESKTOP, unlocked.currentMode)
        assertEquals("O'qituvchi Test", unlocked.teacherName)
        assertEquals(45 * 60, unlocked.activeSecondsRemaining)

        // Lock again
        viewModel.lockBoard()
        val reLocked = viewModel.uiState.value
        assertTrue("Board must be locked after locking", reLocked.isLocked)
        assertEquals(BoardScreenMode.STANDBY_LOCK, reLocked.currentMode)
    }

    @Test
    fun `access control verifies super admin, authorized teachers, and formats logs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessControl = com.example.model.AccessControlManager(context)

        // 1. Super Admin is always recognized and authorized
        val superAdminId = 8453381252L
        assertTrue(accessControl.isSuperAdmin(superAdminId))
        assertTrue(accessControl.isItAdmin(superAdminId))
        assertTrue(accessControl.isAuthorized(superAdminId))

        // 2. Unregistered user is restricted
        val randomUser = 999111222L
        assertFalse(accessControl.isAuthorized(randomUser))

        // 3. Add teacher
        val teacherId = 123456789L
        accessControl.addTeacher(teacherId, "Jasur Rahim")
        assertTrue(accessControl.isTeacher(teacherId))
        assertTrue(accessControl.isAuthorized(teacherId))
        assertEquals("Jasur Rahim", accessControl.getUserDisplayName(teacherId))

        // 4. Access logging format (hh:mm:ss/dd/mm/yyyy)
        accessControl.logAccess(teacherId, "Jasur Rahim", "Dars boshlandi (45 daqiqa)")
        val logs = accessControl.getAccessLogs()
        assertTrue("Logs should contain at least 1 entry", logs.isNotEmpty())
        val latestLog = logs.first()
        assertEquals(teacherId, latestLog.userId)
        assertEquals("Jasur Rahim", latestLog.userName)
        // Verify format matches \d{2}:\d{2}:\d{2}/\d{2}/\d{2}/\d{4}
        val timestampRegex = Regex("""^\d{2}:\d{2}:\d{2}/\d{2}/\d{2}/\d{4}$""")
        assertTrue("Timestamp '${latestLog.timestamp}' must match hh:mm:ss/dd/mm/yyyy", timestampRegex.matches(latestLog.timestamp))

        // 5. Remove teacher
        accessControl.removeTeacher(teacherId)
        assertFalse(accessControl.isAuthorized(randomUser))
    }

    @Test
    fun `it admin management verifies husniddin authorization and add remove`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessControl = com.example.model.AccessControlManager(context)

        // Husniddin (8223415949) is auto-seeded as IT Admin
        val husniddinId = 8223415949L
        assertTrue("Husniddin must be authorized as IT Admin", accessControl.isItAdmin(husniddinId))
        assertTrue("Husniddin must be authorized in system", accessControl.isAuthorized(husniddinId))
        assertEquals("Husniddin", accessControl.getUserDisplayName(husniddinId))

        // Add custom IT admin
        val newAdminId = 9876543210L
        accessControl.addItAdmin(newAdminId, "IT Ma'mur")
        assertTrue(accessControl.isItAdmin(newAdminId))
        assertTrue(accessControl.isAuthorized(newAdminId))

        // Remove custom IT admin
        accessControl.removeItAdmin(newAdminId)
        assertFalse(accessControl.isItAdmin(newAdminId))
    }

    @Test
    fun `room management adds removes and filters rooms properly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessControl = com.example.model.AccessControlManager(context)

        // Initial rooms check (no template rooms, clean slate)
        accessControl.clearRooms()
        val initialRooms = accessControl.getRooms()
        assertTrue("Initial rooms must be empty", initialRooms.isEmpty())

        // Add room
        accessControl.addRoom("1-XONA", "1-Fizika xonasi", "IT Admin Husniddin")
        val updatedRooms = accessControl.getRooms()
        assertTrue(updatedRooms.any { it.id == "1-XONA" && it.name == "1-Fizika xonasi" })

        // Remove room
        val removed = accessControl.removeRoom("1-XONA")
        assertTrue(removed)
        val afterRemoveRooms = accessControl.getRooms()
        assertFalse(afterRemoveRooms.any { it.id == "1-XONA" })
    }

    @Test
    fun `only super admin and it admin have room management rights`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessControl = com.example.model.AccessControlManager(context)

        val superAdminId = 8453381252L
        val itAdminId = 8223415949L
        val teacherId = 1122334455L

        accessControl.addTeacher(teacherId, "O'qituvchi Azamat")

        // Super Admin & IT Admin can manage rooms
        assertTrue("Super Admin has IT Admin privileges", accessControl.isItAdmin(superAdminId))
        assertTrue("Husniddin has IT Admin privileges", accessControl.isItAdmin(itAdminId))

        // Teacher is authorized for lesson sessions but is NOT IT Admin
        assertTrue("Teacher is authorized", accessControl.isAuthorized(teacherId))
        assertTrue("Teacher is recognized as teacher", accessControl.isTeacher(teacherId))
        assertFalse("Teacher cannot be IT Admin", accessControl.isItAdmin(teacherId))
        assertFalse("Teacher cannot be Super Admin", accessControl.isSuperAdmin(teacherId))
    }

    @Test
    fun `device hardware util validates valid and invalid mac addresses`() {
        val validMac1 = "BC:24:11:22:33:44"
        val validMac2 = "00:1A:2B:3C:4D:5E"
        val dummyMac = "02:00:00:00:00:00"
        val zeroMac = "00:00:00:00:00:00"
        val invalidMac = "INVALID_MAC"

        assertTrue(com.example.util.DeviceHardwareUtil.isValidMac(validMac1))
        assertTrue(com.example.util.DeviceHardwareUtil.isValidMac(validMac2))
        assertFalse(com.example.util.DeviceHardwareUtil.isValidMac(dummyMac))
        assertFalse(com.example.util.DeviceHardwareUtil.isValidMac(zeroMac))
        assertFalse(com.example.util.DeviceHardwareUtil.isValidMac(invalidMac))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceMac = com.example.util.DeviceHardwareUtil.getDeviceMacAddress(context)
        assertTrue(deviceMac.isNotBlank())
    }

    @Test
    fun `smartboard registers new room and updates preferences via onBoardRegisteredFromBot`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = SmartBoardViewModel(app)
        val mac = viewModel.deviceMacAddress

        // Trigger registration callback as would come from Telegram bot
        com.example.model.LessonSessionManager.onBoardRegisteredFromBot?.invoke(mac, "105-Informatika xonasi")

        val state = viewModel.uiState.value
        assertEquals("105-Informatika xonasi", state.config.roomName)
        assertEquals(mac, state.config.boardId)
        assertTrue(state.isBoardRegistered)
        assertTrue(state.rooms.any { it.name == "105-Informatika xonasi" })
    }

    @Test
    fun `rooms list provides entries for room selection on unlock and lock`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accessControl = com.example.model.AccessControlManager(context)

        accessControl.addRoom("101-FIZIKA", "101-Fizika xonasi", "Admin")
        accessControl.addRoom("102-KIMYO", "102-Kimyo xonasi", "Admin")

        val rooms = accessControl.getRooms()
        assertTrue(rooms.size >= 2)
        assertTrue(rooms.any { it.id == "101-FIZIKA" })
        assertTrue(rooms.any { it.id == "102-KIMYO" })
    }
}

