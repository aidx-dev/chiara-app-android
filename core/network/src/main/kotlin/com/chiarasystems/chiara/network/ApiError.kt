package com.chiarasystems.chiara.network

/**
 * Port of iOS `APIError`. The default messages are the Spanish source strings; the UI maps the type to a localised
 * string resource ([Server] carries the server's own message).
 */
sealed class ApiError(message: String) : Exception(message) {
    /** The network itself failed (no connection, DNS, timeout): the only error that may be answered from the cache. */
    data object Offline : ApiError("No se pudo conectar con el servidor.")
    data object InvalidCredentials : ApiError("Usuario o contraseña incorrectos.")
    data object SessionExpired : ApiError("Tu sesión ha caducado. Vuelve a iniciar sesión.")
    data object NotSignedIn : ApiError("No hay una sesión iniciada.")
    data class Server(val status: Int, val serverMessage: String) : ApiError(serverMessage)
    data class Unreadable(val what: String) : ApiError("El servidor devolvió una respuesta ilegible ($what).")

    /** True for failures of the network itself (as opposed to a server verdict). */
    val isConnectivity: Boolean get() = this is Offline
}
