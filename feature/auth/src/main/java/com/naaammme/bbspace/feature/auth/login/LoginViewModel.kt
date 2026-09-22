package com.naaammme.bbspace.feature.auth.login

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.naaammme.bbspace.core.model.LoginState
import com.naaammme.bbspace.core.auth.AuthRepository
import com.naaammme.bbspace.core.common.log.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginRepository: AuthRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private var pollJob: Job? = null

    init {
        getQrCode()
    }

    fun getQrCode() {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading

            loginRepository.getQrCode()
                .onSuccess { qrCodeData ->
                    _loginState.value = LoginState.QrCodeReady(qrCodeData)
                    _qrBitmap.value = null
                    launch(Dispatchers.Default) {
                        _qrBitmap.value = generateQrCode(qrCodeData.url, 800)
                    }
                    startPolling(qrCodeData.authCode)
                }
                .onFailure { error ->
                    _loginState.value = LoginState.Error(error.message ?: "获取二维码失败")
                }
        }
    }

    private fun startPolling(authCode: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(2000)

                loginRepository.pollQrCode(authCode)
                    .onSuccess { (status, grant) ->
                        when (status) {
                            0 -> {
                            }
                            1 -> {
                                _loginState.value = LoginState.Scanned
                            }
                            2 -> {
                                grant?.let {
                                    val saved = loginRepository.getSavedCredential()
                                    if (saved == null || saved.mid != it.mid) {
                                        _loginState.value = LoginState.Error("请先用手机号登录当前账号后再扫码绑定 HD key")
                                    } else {
                                        loginRepository.saveHdAccessKey(it.mid, it.accessKey, it.expiresIn)
                                        _loginState.value = LoginState.QrSuccess(it)
                                    }
                                }
                                pollJob?.cancel()
                            }
                        }
                    }
                    .onFailure { error ->
                        // 取消不会再走到这里：AuthRepository 已统一改用
                        // runCatchingCancellable，CancellationException 会原样重抛
                        // （此前 repo 用标准 runCatching 把取消吞成 failure，才需要
                        // 在这里做 `error is CancellationException` 的特判）。
                        _loginState.value = LoginState.Error(error.message ?: "轮询失败")
                        pollJob?.cancel()
                    }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    /**
     * 生成二维码位图。失败返回 null（UI 已有 `_qrBitmap == null` 的占位分支）。
     *
     * 必须包 runCatching：`QRCodeWriter.encode` 在内容过长/无法编码时 **throw
     * WriterException**，`Bitmap.createBitmap` 也可能抛 IllegalArgumentException。
     * 返回类型是 `Bitmap?` 却没有返回 null 的路径 —— 说明本意就是"失败给 null"，
     * 但实际会把异常抛到调用方的协程里（原来是裸的 `launch(Dispatchers.Default)`，
     * 未捕获 → 全局处理器 → 崩溃）。服务端返回异常 url 时扫码页一打开就崩。
     */
    private fun generateQrCode(content: String, size: Int): Bitmap? = runCatching {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] =
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    }.onFailure { t ->
        Logger.w(TAG) { "generateQrCode failed (len=${content.length}, size=$size): ${t.message}" }
    }.getOrNull()

    private companion object {
        const val TAG = "LoginViewModel"
    }
}
