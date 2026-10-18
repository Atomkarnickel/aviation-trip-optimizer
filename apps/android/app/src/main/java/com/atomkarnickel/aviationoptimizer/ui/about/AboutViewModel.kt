package com.atomkarnickel.aviationoptimizer.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atomkarnickel.aviationoptimizer.data.ReleaseRepository
import com.atomkarnickel.aviationoptimizer.domain.ReleaseCheck
import com.atomkarnickel.aviationoptimizer.domain.ReleaseRules
import com.atomkarnickel.aviationoptimizer.domain.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Laufzeitinformationen aus dem tatsächlichen Build (BuildConfig). */
data class AppInfo(val appName: String, val versionName: String, val versionCode: Int, val repository: String)

data class AboutUiState(
    val installedVersion: String,
    val loading: Boolean = true,
    val check: ReleaseCheck? = null,
    val updateState: UpdateState = UpdateState.UNKNOWN,
    val sha256: String? = null,
    val downloadNotice: String? = null,
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val releases: ReleaseRepository,
    val appInfo: AppInfo,
) : ViewModel() {
    private val _state = MutableStateFlow(AboutUiState(installedVersion = appInfo.versionName))
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val check = releases.check(force)
            _state.update {
                it.copy(loading = false, check = check, updateState = ReleaseRules.updateState(appInfo.versionName, check), sha256 = null)
            }
            val ok = check as? ReleaseCheck.Ok ?: return@launch
            ok.latest.checksumUrl?.let { url ->
                val sha = releases.checksum(url)
                _state.update { s -> s.copy(sha256 = sha) }
            }
        }
    }

    /**
     * Liefert die zu öffnende Download-URL – nur wenn sie zum eigenen Repository gehört und HTTPS ist.
     * Der Download/Installationsvorgang wird ausschließlich vom Nutzer im System bestätigt.
     */
    fun downloadUrl(): String? {
        val latest = (_state.value.check as? ReleaseCheck.Ok)?.latest ?: return null
        if (!ReleaseRules.isSafeDownloadUrl(latest.apkUrl, appInfo.repository)) return null
        _state.update {
            it.copy(
                downloadNotice = "Download von ${latest.apkName} gestartet. Öffne die Datei und bestätige die Installation " +
                    "selbst. Die App gilt erst als aktualisiert, wenn hier Version ${latest.version} als installiert angezeigt wird.",
            )
        }
        return latest.apkUrl
    }
}
