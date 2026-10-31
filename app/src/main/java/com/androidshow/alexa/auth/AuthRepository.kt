package com.androidshow.alexa.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fonte única da sessão: login, renovação sob demanda (uma por vez) e logout. */
class AuthRepository(private val api: AuthApi, private val store: SecureStore) {
    private val _session = MutableStateFlow(store.load())
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val mutex = Mutex()

    suspend fun completeLogin(m: Marketplace, id: DeviceIdentity, authorizationCode: String): Session {
        val s = api.register(m, id, authorizationCode)
        update(s)
        return s
    }

    /** Access token válido; renova se faltar pouco para expirar ou se [force] (ex.: 401/403 do AVS). */
    suspend fun accessToken(force: Boolean = false): String = mutex.withLock {
        val current = _session.value ?: throw AuthException("Não autenticado")
        if (!force && current.accessValid()) return@withLock current.accessToken
        val refreshed = api.refresh(current)
        update(refreshed)
        refreshed.accessToken
    }

    /** Cookies do site; troca o refresh token por cookies novos se ainda não houver. */
    suspend fun cookies(force: Boolean = false): Map<String, String> = mutex.withLock {
        val current = _session.value ?: throw AuthException("Não autenticado")
        if (!force && current.cookies.isNotEmpty()) return@withLock current.cookies
        val cookies = api.exchangeCookies(current)
        update(current.copy(cookies = cookies))
        cookies
    }

    fun logout() {
        store.clear()
        _session.value = null
    }

    private fun update(s: Session) {
        store.save(s)
        _session.value = s
    }
}
