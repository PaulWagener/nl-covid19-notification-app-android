/*
 * Copyright (c) 2020 De Staat der Nederlanden, Ministerie van Volksgezondheid, Welzijn en Sport.
 *  Licensed under the EUROPEAN UNION PUBLIC LICENCE v. 1.2
 *
 *  SPDX-License-Identifier: EUPL-1.2
 */
package nl.rijksoverheid.en.enapi

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes
import com.google.android.gms.nearby.exposurenotification.ExposureSummary
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wrapper around [ExposureNotificationClient] using suspend functions.
 */
class ExposureNotificationApi(private val client: ExposureNotificationClient) {
    /**
     * Get the status of the exposure notifications api
     * @return the status
     */
    suspend fun getStatus(): StatusResult = suspendCoroutine { c ->
        client.isEnabled.addOnSuccessListener {
            if (it) {
                c.resume(StatusResult.Enabled)
            } else {
                c.resume(StatusResult.Disabled)
            }
        }.addOnFailureListener {
            Timber.e(it, "Error getting API status")
            val apiException = it as? ApiException
            c.resume(
                when (apiException?.statusCode) {
                    ExposureNotificationStatusCodes.API_NOT_CONNECTED -> StatusResult.Unavailable(
                        apiException.getMostSpecificStatusCode()
                    )
                    else -> StatusResult.UnknownError(it)
                }
            )
        }
    }

    /**
     * Request to enable Exposure Notifications
     * @return the result of the request
     */
    suspend fun requestEnableNotifications(): EnableNotificationsResult = suspendCoroutine { c ->
        client.start().addOnSuccessListener {
            c.resume(EnableNotificationsResult.Enabled)
        }.addOnFailureListener {
            val apiException = it as? ApiException
            c.resume(
                when (apiException?.statusCode) {
                    CommonStatusCodes.RESOLUTION_REQUIRED -> EnableNotificationsResult.ResolutionRequired(
                        apiException.status.resolution!!
                    )
                    else -> {
                        Timber.e(it, "Error while enabling notifications")
                        EnableNotificationsResult.UnknownError(it)
                    }
                }
            )
        }
    }

    /**
     * Request to disable exposure notifications
     * @return the result
     */
    suspend fun disableNotifications(): DisableNotificationsResult = suspendCoroutine { c ->
        client.stop().addOnSuccessListener {
            c.resume(DisableNotificationsResult.Disabled)
        }.addOnFailureListener {
            Timber.e(it, "Error while disabling notifications")
            c.resume(
                // Technically we could get a connection error, but this is not
                // really expected, since this is only called when previously enabled.
                DisableNotificationsResult.UnknownError(it)
            )
        }
    }

    /**
     * Request the tempoary exposure key history
     * @return the result. For the initial call is is most likely [TemporaryExposureKeysResult.RequireConsent]
     */
    suspend fun requestTemporaryExposureKeyHistory(): TemporaryExposureKeysResult = suspendCoroutine { c ->
        client.temporaryExposureKeyHistory.addOnSuccessListener {
            c.resume(TemporaryExposureKeysResult.Success(it))
        }.addOnFailureListener {
            val apiException = it as? ApiException
            c.resume(
                when (apiException?.statusCode) {
                    ExposureNotificationStatusCodes.RESOLUTION_REQUIRED -> TemporaryExposureKeysResult.RequireConsent(
                        apiException.status.resolution!!
                    )
                    else -> TemporaryExposureKeysResult.UnknownError(it)
                }
            )
        }
    }

    /**
     * Provide the diagnostics keys for exposure notifications matching
     *
     * @param files the list of files to process. The files will be deleted when processing is successful
     * @param configuration the configuration to use for matching
     * @param token token that will be returned as [ExposureNotificationClient.EXTRA_TOKEN] when a match occurs
     * @return the result
     */
    suspend fun provideDiagnosisKeys(
        files: List<File>,
        configuration: ExposureConfiguration,
        token: String
    ) = suspendCoroutine<DiagnosisKeysResult> { c ->
        client.provideDiagnosisKeys(files, configuration, token).addOnSuccessListener {
            files.forEach { it.delete() }
            c.resume(DiagnosisKeysResult.Success)
        }.addOnFailureListener {
            Timber.e(it, "Error while providing diagnosis keys")
            val apiException = it as? ApiException
            c.resume(
                when (apiException?.statusCode) {
                    ExposureNotificationStatusCodes.FAILED_DISK_IO -> DiagnosisKeysResult.FailedDiskIo
                    else -> DiagnosisKeysResult.UnknownError(it)
                }
            )
        }
    }

    /**
     * Get the [ExposureSummary] by token
     * @param token the token passed to [provideDiagnosisKeys] and from [ExposureNotificationClient.EXTRA_TOKEN]
     * @return the summary or null if there's no match or an error occurred
     */
    suspend fun getSummary(token: String): ExposureSummary? = suspendCoroutine<ExposureSummary?> { c ->
        client.getExposureSummary(token).addOnSuccessListener {
            c.resume(it)
        }.addOnFailureListener {
            Timber.e(it, "Error getting ExposureSummary")
            // TODO determine if we want bubble up errors here; this is used
            // when processing the notification and at that point the API should never return
            // null. If it does or throws an error, all we can do is retry or give up
            c.resume(null)
        }
    }
}

/**
 * Try to get the (more specific) status code if this [ApiException] has status [CommonStatusCodes.API_NOT_CONNECTED]
 * @return the status code that was parsed in case of [CommonStatusCodes.API_NOT_CONNECTED] or the original status code otherwise
 */
private fun ApiException.getMostSpecificStatusCode(): Int {
    val statusMessage = status.statusMessage
    if (statusCode == ExposureNotificationStatusCodes.API_NOT_CONNECTED && statusMessage != null) {
        val matches = Regex("ConnectionResult\\{statusCode=[a-zA-Z0-9_]+\\(([0-9]+)\\),").find(
            statusMessage
        )
        if (matches != null && matches.groupValues.size == 2) {
            return matches.groupValues[1].toIntOrNull() ?: statusCode
        }
    }
    return statusCode
}
