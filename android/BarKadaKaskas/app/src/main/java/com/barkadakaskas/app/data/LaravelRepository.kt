package com.barkadakaskas.app.data

import android.os.Build
import com.barkadakaskas.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** A failure the user can act on; the message is shown as-is in the app. */
class ApiException(message: String, val status: Int = 0) : Exception(message)

/** Talks to the Laravel API (/api/v1) with a bearer token. See docs/mobile-api.md. */
class LaravelRepository(private val store: SessionStore) : TravelRepository {
    override val serverUrl: String get() = store.serverUrl
    override val hasSession: Boolean get() = store.token != null

    override suspend fun configureServer(url: String) {
        val trimmed = url.trim().trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        runCatching { URL(normalized) }.getOrElse { throw ApiException("That server address doesn't look right.") }
        if (normalized != store.serverUrl) store.token = null // a token only works on the server that issued it
        store.serverUrl = normalized
    }

    override suspend fun login(email: String, password: String): User {
        val body = request("POST", "/auth/login", JSONObject().put("email", email.trim()).put("password", password).put("device_name", deviceName()), auth = false)!!
        store.token = body.getString("token")
        return ApiJson.user(body.getJSONObject("user"))
    }

    override suspend fun register(name: String, email: String, password: String): User {
        val body = request("POST", "/auth/register", JSONObject().put("name", name.trim()).put("email", email.trim()).put("password", password).put("password_confirmation", password).put("device_name", deviceName()), auth = false)!!
        store.token = body.getString("token")
        return ApiJson.user(body.getJSONObject("user"))
    }

    override suspend fun currentUser(): User = ApiJson.user(request("GET", "/auth/me")!!.getJSONObject("user"))

    override suspend fun logout() {
        // Revoke this device's token on the server, but always sign out locally.
        try { if (hasSession) request("POST", "/auth/logout") } catch (_: ApiException) { } finally { store.token = null }
    }

    override suspend fun trips(): List<Trip> = ApiJson.trips(request("GET", "/trips")!!.optJSONArray("data"))
    override suspend fun createTrip(draft: TripDraft): Trip = ApiJson.trip(data(request("POST", "/trips", ApiJson.tripBody(draft))))
    override suspend fun trip(id: Long): TripDetail = ApiJson.tripDetail(data(request("GET", "/trips/$id")))

    override suspend fun saveStop(tripId: Long, stopId: Long?, draft: StopDraft) {
        if (stopId == null) request("POST", "/trips/$tripId/itinerary", ApiJson.stopBody(draft))
        else request("PATCH", "/trips/$tripId/itinerary/$stopId", ApiJson.stopBody(draft))
    }
    override suspend fun documents(tripId: Long): List<TravelerDocument> = ApiJson.documents(request("GET", "/trips/$tripId/documents"))
    override suspend fun uploadDocument(tripId: Long, type: String, label: String, visibility: String, bytes: ByteArray, mimeType: String, fileName: String): TravelerDocument =
        ApiJson.document(data(request("POST", "/trips/$tripId/documents", raw = multipart(mapOf("type" to type, "label" to label.trim(), "visibility" to visibility), listOf(FilePart("file", fileName, mimeType, bytes))))))
    override suspend fun setDocumentVisibility(tripId: Long, documentId: Long, visibility: String): TravelerDocument =
        ApiJson.document(data(request("PATCH", "/trips/$tripId/documents/$documentId", JSONObject().put("visibility", visibility))))
    override suspend fun deleteDocument(tripId: Long, documentId: Long) { request("DELETE", "/trips/$tripId/documents/$documentId") }
    override suspend fun documentFile(filePath: String): ByteArray = withContext(Dispatchers.IO) {
        val base = store.serverUrl.ifBlank { throw ApiException("Enter your server address first.") }
        val token = store.token ?: throw ApiException("Please log in again.", 401)
        val connection = URL("$base/api/v1$filePath").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            if (status !in 200..299) throw ApiException(friendlyError(status, null), status)
            connection.inputStream.use { it.readBytes() }
        } catch (failure: ApiException) { throw failure }
        catch (_: IOException) { throw ApiException("Can't reach the server. Check your internet connection.") }
        finally { connection.disconnect() }
    }

    override suspend fun suggestStops(tripId: Long, prompt: String): PlanSuggestion =
        request("POST", "/trips/$tripId/itinerary/suggestions", JSONObject().put("prompt", prompt.trim()), readTimeout = 100_000).let { PlanSuggestion(ApiJson.suggestions(it), ApiJson.changes(it)) }
    override suspend fun addStops(tripId: Long, stops: List<StopSuggestion>, changes: List<StopChange>) { request("POST", "/trips/$tripId/itinerary/batch", ApiJson.batchBody(stops, changes)) }
    override suspend fun setStopDone(tripId: Long, stopId: Long, done: Boolean) { request("PATCH", "/trips/$tripId/itinerary/$stopId/done", JSONObject().put("done", done)) }

    override suspend fun createExpense(tripId: Long, draft: ExpenseDraft) { request("POST", "/trips/$tripId/expenses", ApiJson.expenseBody(draft)) }
    override suspend fun updateProfile(draft: ProfileDraft) { request("PATCH", "/me/profile", ApiJson.profileBody(draft)) }
    override suspend fun uploadAvatar(jpeg: ByteArray) { request("POST", "/me/avatar", raw = multipart("avatar", "avatar.jpg", "image/jpeg", jpeg)) }
    override suspend fun removeAvatar() { request("DELETE", "/me/avatar") }
    override suspend fun posts(tripId: Long, before: Long?): PostPage = ApiJson.postPage(request("GET", "/trips/$tripId/posts" + (before?.let { "?before=$it" } ?: "")) ?: JSONObject())
    override suspend fun createPost(tripId: Long, caption: String, jpegs: List<ByteArray>): Post =
        ApiJson.post(data(request("POST", "/trips/$tripId/posts", raw = multipart(mapOf("caption" to caption.trim()), jpegs.mapIndexed { i, bytes -> FilePart("photos[]", "photo-${i + 1}.jpg", "image/jpeg", bytes) }))))
    override suspend fun deletePost(tripId: Long, postId: Long) { request("DELETE", "/trips/$tripId/posts/$postId") }
    override suspend fun toggleLike(tripId: Long, postId: Long): Pair<Boolean, Int> = data(request("POST", "/trips/$tripId/posts/$postId/like")).let { it.optBoolean("liked") to it.optInt("likes_count") }
    override suspend fun alerts(after: Long?): AlertPage = ApiJson.alertPage(request("GET", "/alerts" + (after?.let { "?after=$it" } ?: "")))
    override suspend fun markAlertsRead(ids: List<Long>): Int =
        request("POST", "/alerts/read", JSONObject().put("ids", JSONArray().apply { ids.forEach { put(it) } }))?.optInt("unread_count") ?: 0
    override suspend fun aiEditPhoto(jpeg: ByteArray, style: String?, prompt: String?): Pair<ByteArray, AiQuota?> {
        val fields = buildMap { put("subject", "photo"); style?.let { put("style", it) }; prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", it.trim()) } }
        val body = request("POST", "/photos/restyle", raw = multipart(fields, listOf(FilePart("photo", "photo.jpg", "image/jpeg", jpeg))), readTimeout = 150_000)
            ?: throw ApiException("The AI sent no image. Try again.")
        val image = body.optJSONObject("data")?.optString("image").orEmpty().substringAfter("base64,", "")
        if (image.isBlank()) throw ApiException("The AI sent no image. Try again.")
        return android.util.Base64.decode(image, android.util.Base64.DEFAULT) to ApiJson.aiQuota(body.optJSONObject("ai"))
    }
    override suspend fun highlight(tripId: Long, date: String?): Highlight = ApiJson.highlight(data(request("GET", "/trips/$tripId/highlights" + (date?.let { "?date=$it" } ?: ""))))
    override suspend fun highlightCover(tripId: Long, date: String): String =
        data(request("POST", "/trips/$tripId/highlights/cover?date=$date", readTimeout = 150_000)).getString("cover_url").let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }
    override suspend fun stopPhotos(tripId: Long, stopId: Long): List<PlacePhoto> =
        request("GET", "/trips/$tripId/itinerary/$stopId/photos", readTimeout = 60_000)?.optJSONArray("data").let { list -> ApiJson.placePhotos(list) }
    override suspend fun stopTips(tripId: Long, stopId: Long): List<PlaceTip> =
        request("GET", "/trips/$tripId/itinerary/$stopId/tips", readTimeout = 45_000)?.optJSONArray("data").mapObjects { PlaceTip(it.optString("title"), it.optString("detail").takeIf { d -> d.isNotBlank() && d != "null" }) }.filter { it.title.isNotBlank() }
    override suspend fun aiQuota(): AiQuota = ApiJson.aiQuota(data(request("GET", "/photos/ai-quota"))) ?: AiQuota(3, 3, 0)
    override suspend fun comments(tripId: Long, postId: Long): List<PostComment> = ApiJson.comments(request("GET", "/trips/$tripId/posts/$postId/comments"))
    override suspend fun addComment(tripId: Long, postId: Long, body: String): Pair<PostComment, Int> =
        request("POST", "/trips/$tripId/posts/$postId/comments", JSONObject().put("body", body.trim()))!!.let { ApiJson.comment(it.getJSONObject("data")) to it.optInt("comments_count") }
    override suspend fun deleteComment(tripId: Long, postId: Long, commentId: Long): Int =
        request("DELETE", "/trips/$tripId/posts/$postId/comments/$commentId")?.optInt("comments_count") ?: 0
    override suspend fun deleteExpense(tripId: Long, expenseId: Long) { request("DELETE", "/trips/$tripId/expenses/$expenseId") }
    override suspend fun scanReceipt(tripId: Long, jpeg: ByteArray): ReceiptDraft = ApiJson.receiptDraft(data(request("POST", "/trips/$tripId/receipt-scans", raw = multipart("receipt", "receipt.jpg", "image/jpeg", jpeg))))

    override suspend fun changeExpenseStatus(tripId: Long, expense: Expense, status: String, reason: String?, voidPayments: Boolean) {
        val body = JSONObject().put("status", status).put("version", expense.version).put("reason", reason ?: JSONObject.NULL).put("void_payments", voidPayments)
        request("PATCH", "/trips/$tripId/expenses/${expense.id}/status", body)
    }

    override suspend fun markSharePaid(tripId: Long, expense: Expense, memberId: Long, settlementId: Long?, newPayment: Boolean) {
        val body = JSONObject().put("member_id", memberId).put("version", expense.version).put("settlement_id", settlementId ?: JSONObject.NULL).put("new_payment", newPayment)
        request("POST", "/trips/$tripId/expenses/${expense.id}/shares/paid", body)
    }

    override suspend fun setExpensePayers(tripId: Long, expense: Expense, payments: List<Payment>) {
        val rows = org.json.JSONArray().apply { payments.forEach { put(JSONObject().put("trip_member_id", it.memberId).put("amount_minor", it.amountMinor)) } }
        request("PUT", "/trips/$tripId/expenses/${expense.id}/payers", JSONObject().put("version", expense.version).put("payments", rows))
    }

    override suspend fun recordMerchantPayment(tripId: Long, expense: Expense, memberId: Long, amountMinor: Long) {
        request("POST", "/trips/$tripId/expenses/${expense.id}/payments", JSONObject().put("trip_member_id", memberId).put("amount_minor", amountMinor).put("version", expense.version))
    }

    override suspend fun recordSettlement(tripId: Long, recipientId: Long, amountMinor: Long, method: String) {
        request("POST", "/trips/$tripId/settlements", JSONObject().put("to_member_id", recipientId).put("amount_minor", amountMinor).put("payment_method", method))
    }

    override suspend fun confirmSettlement(tripId: Long, settlementId: Long) { request("POST", "/trips/$tripId/settlements/$settlementId/confirm") }

    override suspend fun createInvitation(tripId: Long): String = data(request("POST", "/trips/$tripId/invitations")).getString("url")

    override suspend fun updateTrip(tripId: Long, draft: TripDraft) { request("PATCH", "/trips/$tripId", ApiJson.tripBody(draft)) }
    override suspend fun uploadCover(tripId: Long, jpeg: ByteArray) { request("POST", "/trips/$tripId/cover", raw = multipart("cover", "cover.jpg", "image/jpeg", jpeg)) }
    override suspend fun removeCover(tripId: Long) { request("DELETE", "/trips/$tripId/cover") }
    override suspend fun cancelTrip(tripId: Long, reason: String?, hide: Boolean) { request("POST", "/trips/$tripId/cancel", ApiJson.cancelBody(reason, hide)) }
    override suspend fun reopenTrip(tripId: Long) { request("POST", "/trips/$tripId/reopen") }
    override suspend fun setTripHidden(tripId: Long, hidden: Boolean) { request("PATCH", "/trips/$tripId/visibility", org.json.JSONObject().put("hidden", hidden)) }

    private fun data(body: JSONObject?): JSONObject = body?.optJSONObject("data") ?: throw ApiException("The server sent an unexpected response.")

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120).ifBlank { "Android" }

    /** Sends one JSON request. Returns the parsed body, or null for 204 No Content. */
    /** A non-JSON request body, e.g. a multipart photo upload. */
    private class Payload(val contentType: String, val bytes: ByteArray)

    /** multipart/form-data with a single file field. */
    /** One file in a multipart body. */
    private class FilePart(val field: String, val fileName: String, val mimeType: String, val bytes: ByteArray)

    /** multipart/form-data with text fields and files. */
    private fun multipart(fields: Map<String, String>, files: List<FilePart>): Payload {
        val boundary = "BarKada" + java.util.UUID.randomUUID().toString().replace("-", "")
        val out = java.io.ByteArrayOutputStream()
        fields.forEach { (name, value) -> out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8)) }
        files.forEach { part ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"${part.field}\"; filename=\"${part.fileName}\"\r\nContent-Type: ${part.mimeType}\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(part.bytes); out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return Payload("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    /** multipart/form-data with a single file field. */
    private fun multipart(field: String, fileName: String, mimeType: String, bytes: ByteArray): Payload = multipart(emptyMap(), listOf(FilePart(field, fileName, mimeType, bytes)))

    private suspend fun request(method: String, path: String, body: JSONObject? = null, auth: Boolean = true, raw: Payload? = null, readTimeout: Int = 30_000): JSONObject? = withContext(Dispatchers.IO) {
        val base = store.serverUrl.ifBlank { throw ApiException("Enter your server address first.") }
        val token = store.token
        if (auth && token == null) throw ApiException("Please log in again.", 401)
        val connection = try { URL("$base/api/v1$path").openConnection() as HttpURLConnection } catch (_: IOException) { throw ApiException("That server address doesn't look right.") }
        try {
            // HttpURLConnection has no PATCH; Laravel honours the override header on POST.
            connection.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeout
            connection.setRequestProperty("Accept", "application/json")
            if (auth) connection.setRequestProperty("Authorization", "Bearer $token")
            if (raw != null) {
                connection.doOutput = true
                connection.readTimeout = maxOf(readTimeout, 60_000)
                connection.setRequestProperty("Content-Type", raw.contentType)
                connection.setFixedLengthStreamingMode(raw.bytes.size)
                connection.outputStream.use { it.write(raw.bytes) }
            } else if (body != null || method != "GET") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }
            if (status in 200..299) {
                return@withContext if (status == 204 || text.isNullOrBlank()) null else parse(text)
            }
            if (status == 401 && auth) store.token = null
            throw ApiException(friendlyError(status, ApiJson.errorMessage(text)), status)
        } catch (failure: ApiException) {
            throw failure
        } catch (_: SocketTimeoutException) {
            throw ApiException("The server took too long to respond. Please try again.")
        } catch (_: IOException) {
            throw ApiException("Can't reach the server. Check the address and your internet connection.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(text: String): JSONObject = runCatching { JSONObject(text) }.getOrElse {
        // Lists are always wrapped in { data: [...] } by the API, but be tolerant.
        runCatching { JSONObject().put("data", JSONArray(text)) }.getOrElse { throw ApiException("The server sent an unexpected response.") }
    }

    private fun friendlyError(status: Int, message: String?): String = when (status) {
        401 -> "Your session has ended. Please log in again."
        403 -> message?.takeUnless { it == "This action is unauthorized." } ?: "You don't have permission to do that."
        404 -> "That item no longer exists. Refresh and try again."
        409, 422 -> message ?: "Please check the details and try again."
        410 -> message ?: "This invitation has expired."
        429 -> message ?: "Too many attempts. Please wait a minute and try again."
        in 500..599 -> "The server had a problem. Please try again later."
        else -> message ?: "Something went wrong (HTTP $status)."
    }
}
