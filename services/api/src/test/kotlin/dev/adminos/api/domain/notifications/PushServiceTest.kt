package dev.adminos.api.domain.notifications

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushServiceTest {

    private lateinit var deviceRepo: InMemoryDeviceRepository
    private lateinit var pushService: PushService

    private val testUserId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        deviceRepo = InMemoryDeviceRepository()
        pushService = PushService(deviceRepo)
    }

    @Test
    fun `sendToUser returns failure when no devices registered`() = runBlocking {
        val result = pushService.sendToUser(testUserId, "Test", "Body")
        assertFalse(result.success)
    }

    @Test
    fun `sendToUser returns failure when devices have no FCM token`() = runBlocking {
        deviceRepo.save(Device(userId = testUserId, fcmToken = null))

        val result = pushService.sendToUser(testUserId, "Test", "Body")
        assertFalse(result.success)
    }

    @Test
    fun `sendToUser succeeds with valid FCM token`() = runBlocking {
        deviceRepo.save(Device(userId = testUserId, fcmToken = "valid_token_123"))

        val result = pushService.sendToUser(testUserId, "Test", "Body")
        assertTrue(result.success)
        assertTrue(result.messageId != null)
    }

    @Test
    fun `sendToDevice removes invalid token`() = runBlocking {
        val device = Device(userId = testUserId, fcmToken = "INVALID_token_abc")
        deviceRepo.save(device)

        pushService.sendToUser(testUserId, "Test", "Body")

        // Token should be removed
        val updated = deviceRepo.findById(device.id)
        assertNull(updated?.fcmToken)
    }

    @Test
    fun `sendToUser handles mixed valid and invalid tokens`() = runBlocking {
        val validDevice = Device(userId = testUserId, fcmToken = "valid_token_123")
        val invalidDevice = Device(userId = testUserId, fcmToken = "INVALID_token_abc")
        deviceRepo.save(validDevice)
        deviceRepo.save(invalidDevice)

        val result = pushService.sendToUser(testUserId, "Test", "Body")
        assertTrue(result.success) // at least one succeeded

        // Invalid token should be removed
        val updatedInvalid = deviceRepo.findById(invalidDevice.id)
        assertNull(updatedInvalid?.fcmToken)
    }
}
