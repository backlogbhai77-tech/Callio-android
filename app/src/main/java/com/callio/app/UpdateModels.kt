package com.callio.app.update

/** Parsed `version.json` published with every GitHub release. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val type: String,
    val minSupportedVersionCode: Long,
    val lastMajorVersionCode: Long,
    val notes: String
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo, val forced: Boolean, val major: Boolean) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUiState
    data class Installing(val info: UpdateInfo) : UpdateUiState
    data class NeedsPermission(val info: UpdateInfo) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}
