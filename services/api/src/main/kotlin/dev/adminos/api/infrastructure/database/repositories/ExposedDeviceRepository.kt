package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.notifications.Device
import dev.adminos.api.domain.notifications.DeviceRepository
import dev.adminos.api.infrastructure.database.tables.DevicesTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
import java.time.Instant
import java.util.UUID

class ExposedDeviceRepository : DeviceRepository {

    override suspend fun findByUserId(userId: UUID): List<Device> = dbQuery {
        DevicesTable.selectAll().where { DevicesTable.userId eq userId }.map { it.toDevice() }
    }

    override suspend fun findById(id: UUID): Device? = dbQuery {
        DevicesTable.selectAll().where { DevicesTable.id eq id }.singleOrNull()?.toDevice()
    }

    override suspend fun save(device: Device): Device = dbQuery {
        DevicesTable.insert {
            it[id] = device.id
            it[userId] = device.userId
            it[deviceType] = device.deviceType
            it[deviceName] = device.deviceName
            it[fcmToken] = device.fcmToken
            it[appVersion] = device.appVersion
            it[osVersion] = device.osVersion
            it[smsPermission] = device.smsPermission
            it[createdAt] = device.createdAt
            it[updatedAt] = device.updatedAt
        }
        device
    }

    override suspend fun update(device: Device): Device = dbQuery {
        val now = Instant.now()
        DevicesTable.update({ DevicesTable.id eq device.id }) {
            it[deviceType] = device.deviceType
            it[deviceName] = device.deviceName
            it[fcmToken] = device.fcmToken
            it[appVersion] = device.appVersion
            it[osVersion] = device.osVersion
            it[smsPermission] = device.smsPermission
            it[updatedAt] = now
        }
        device.copy(updatedAt = now)
    }

    override suspend fun removeToken(deviceId: UUID): Unit = dbQuery {
        DevicesTable.update({ DevicesTable.id eq deviceId }) {
            it[fcmToken] = null
            it[updatedAt] = Instant.now()
        }
    }

    private fun ResultRow.toDevice() = Device(
        id = this[DevicesTable.id],
        userId = this[DevicesTable.userId],
        deviceType = this[DevicesTable.deviceType],
        deviceName = this[DevicesTable.deviceName],
        fcmToken = this[DevicesTable.fcmToken],
        appVersion = this[DevicesTable.appVersion],
        osVersion = this[DevicesTable.osVersion],
        smsPermission = this[DevicesTable.smsPermission],
        createdAt = this[DevicesTable.createdAt],
        updatedAt = this[DevicesTable.updatedAt]
    )
}
