package com.autom8ed.auth

interface TokenStorage {
    suspend fun save(token: AuthToken)
    suspend fun load(): AuthToken?
    suspend fun clear()
}
