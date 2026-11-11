package com.awaken.sightseeingtravel.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.awaken.sightseeingtravel.BuildConfig
import com.awaken.sightseeingtravel.core.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Retrofit2 Service matching the locked `/api/v1` Backend API contract.
 */
interface SightseeingApiService {

    // ------------------------------------------------------------------------
    // Authentication & User Profile
    // ------------------------------------------------------------------------

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiEnvelope<AuthTokensResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): ApiEnvelope<AuthTokensResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): ApiEnvelope<AuthTokensResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body request: LogoutRequest): retrofit2.Response<ResponseBody>

    @GET("api/v1/users/me")
    suspend fun getMyProfile(): ApiEnvelope<UserProfileDto>

    @PUT("api/v1/users/me")
    suspend fun updateMyProfile(@Body request: UpdateProfileRequest): ApiEnvelope<UserProfileDto>

    // ------------------------------------------------------------------------
    // POIs, Cultural Stories & Spatial AR Context
    // ------------------------------------------------------------------------

    @GET("api/v1/pois/nearby")
    suspend fun getNearbyPois(
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
        @Query("radiusKm") radiusKm: Double = 50.0,
        @Query("lang") language: String = "vi"
    ): ApiEnvelope<List<PoiPinSummaryDto>>

    @GET("api/v1/pois/{poiId}")
    suspend fun getPoiDetail(
        @Path("poiId") poiId: String,
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
        @Query("lang") language: String = "vi"
    ): ApiEnvelope<PoiDetailDto>

    @GET("api/v1/pois/{poiId}/ar-context")
    suspend fun getPoiArContext(
        @Path("poiId") poiId: String,
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
        @Query("lang") language: String = "vi"
    ): ApiEnvelope<PoiArContextDto>

    @POST("api/v1/navigation/route")
    suspend fun calculateRoute(
        @Body request: CalculateRouteRequestDto
    ): ApiEnvelope<CalculatedRouteDto>

    // ------------------------------------------------------------------------
    // Community Photos (Contabo Object Storage via Backend Multipart API)
    // ------------------------------------------------------------------------

    @GET("api/v1/pois/{poiId}/photos")
    suspend fun getPoiPhotos(
        @Path("poiId") poiId: String,
        @Query("sortBy") sortBy: String = "popular", // "popular" or "latest"
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): ApiEnvelope<List<CommunityPhotoDto>>

    @Multipart
    @POST("api/v1/pois/{poiId}/photos")
    suspend fun uploadCommunityPhoto(
        @Path("poiId") poiId: String,
        @Part file: MultipartBody.Part,
        @Part("capturedLatitude") capturedLatitude: RequestBody,
        @Part("capturedLongitude") capturedLongitude: RequestBody,
        @Part("azimuthDegrees") azimuthDegrees: RequestBody,
        @Part("pitchDegrees") pitchDegrees: RequestBody,
        @Part("caption") caption: RequestBody
    ): ApiEnvelope<CommunityPhotoDto>

    @POST("api/v1/photos/{photoId}/like")
    suspend fun toggleLikePhoto(
        @Path("photoId") photoId: String
    ): ApiEnvelope<LikePhotoResponseDto>

    @POST("api/v1/photos/{photoId}/report")
    suspend fun reportPhoto(
        @Path("photoId") photoId: String,
        @Body request: ReportPhotoRequest
    ): ApiEnvelope<ReportPhotoResponseDto>

    @DELETE("api/v1/photos/{photoId}")
    suspend fun deleteOwnPhoto(
        @Path("photoId") photoId: String
    ): retrofit2.Response<ResponseBody>

    // ------------------------------------------------------------------------
    // Admin & SuperAdmin POI Management, Approval & Photo Moderation
    // ------------------------------------------------------------------------

    @GET("api/v1/admin/pois")
    suspend fun getAdminPois(
        @Query("status") status: String? = null
    ): ApiEnvelope<List<AdminPoiDto>>

    @POST("api/v1/admin/pois")
    suspend fun createPoi(
        @Body request: CreateOrUpdatePoiRequest
    ): ApiEnvelope<AdminPoiDto>

    @PUT("api/v1/admin/pois/{poiId}")
    suspend fun updatePoi(
        @Path("poiId") poiId: String,
        @Body request: CreateOrUpdatePoiRequest
    ): ApiEnvelope<AdminPoiDto>

    @DELETE("api/v1/admin/pois/{poiId}")
    suspend fun deletePoi(
        @Path("poiId") poiId: String
    ): Response<ResponseBody>

    @Multipart
    @POST("api/v1/admin/pois/{poiId}/cover")
    suspend fun uploadPoiCoverImage(
        @Path("poiId") poiId: String,
        @Part file: MultipartBody.Part
    ): Response<AdminPoiDto>

    @GET("api/v1/admin/pois/{poiId}/ar-cards")
    suspend fun getAdminArInfoCards(
        @Path("poiId") poiId: String
    ): ApiEnvelope<List<AdminArInfoCardDto>>

    @GET("api/v1/admin/ar-models")
    suspend fun getAdminArModelLibrary(): Response<List<ArModelLibraryItemDto>>

    @POST("api/v1/admin/pois/{poiId}/placed-ar-model")
    suspend fun placeExistingArModel(
        @Path("poiId") poiId: String,
        @Body request: PlaceArModelRequest
    ): Response<AdminArInfoCardDto>

    @POST("api/v1/admin/pois/{poiId}/ar-cards")
    suspend fun createArInfoCard(
        @Path("poiId") poiId: String,
        @Body request: UpsertArInfoCardRequest
    ): retrofit2.Response<ResponseBody>

    @PUT("api/v1/admin/ar-cards/{cardId}")
    suspend fun updateArInfoCard(
        @Path("cardId") cardId: String,
        @Body request: UpsertArInfoCardRequest
    ): Response<AdminArInfoCardDto>

    @DELETE("api/v1/admin/ar-cards/{cardId}")
    suspend fun deleteArInfoCard(
        @Path("cardId") cardId: String
    ): Response<ResponseBody>

    @Multipart
    @POST("api/v1/admin/ar-cards/{cardId}/3d-model")
    suspend fun submitArCard3dModel(
        @Path("cardId") cardId: String,
        @Part images: List<MultipartBody.Part>
    ): Response<ArModelBuildSubmissionDto>

    @GET("api/v1/admin/ar-cards/{cardId}/3d-model-status")
    suspend fun getArCard3dModelStatus(
        @Path("cardId") cardId: String
    ): Response<ArModelBuildStatusDto>

    /**
     * SuperAdmin ONLY: Approves a `PendingApproval` POI and sets status to `Published`
     */
    @POST("api/v1/admin/pois/{poiId}/approve")
    suspend fun approvePoi(
        @Path("poiId") poiId: String
    ): ApiEnvelope<AdminPoiDto>

    /**
     * SuperAdmin ONLY: Rejects a `PendingApproval` POI with mandatory rejection reason
     */
    @POST("api/v1/admin/pois/{poiId}/reject")
    suspend fun rejectPoi(
        @Path("poiId") poiId: String,
        @Body request: RejectPoiRequest
    ): ApiEnvelope<AdminPoiDto>

    @GET("api/v1/admin/moderation/photos")
    suspend fun getReportedPhotos(): ApiEnvelope<List<ModerationPhotoItemDto>>

    @POST("api/v1/admin/moderation/photos/{photoId}/decision")
    suspend fun moderatePhoto(
        @Path("photoId") photoId: String,
        @Body request: ModeratePhotoDecisionRequest
    ): ApiEnvelope<ModerationPhotoItemDto>
}

/**
 * Synchronous Refresh API for OkHttp Authenticator when a 401 response occurs.
 */
internal interface SyncTokenRefreshApi {
    @POST("api/v1/auth/refresh")
    fun refreshSync(@Body request: RefreshTokenRequest): retrofit2.Call<ApiEnvelope<AuthTokensResponse>>
}

/**
 * Factory & Multipart Image Compression Helper for `SightseeingApiService`.
 */
object SightseeingNetworkModule {

    private const val TARGET_PHOTO_BYTES = 300 * 1024 // 300 KiB
    private const val MAX_DECODED_PHOTO_PIXELS = 4_000_000L

    fun createApiService(tokenStore: TokenStore): SightseeingApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            level = if (BuildConfig.DEBUG) {
                // Request bodies contain login/register passwords and must never reach Logcat.
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }

        val gson = ApiResponseAdapters.createGson()
        val refreshRetrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        val syncRefreshApi = refreshRetrofit.create(SyncTokenRefreshApi::class.java)

        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val token = tokenStore.accessToken
            val requestBuilder = original.newBuilder()
                .header("Accept", "application/json")
                .header("Accept-Language", tokenStore.preferredLanguage)

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }

        val tokenAuthenticator = Authenticator { _, response ->
            if (responseCount(response) >= 2) {
                return@Authenticator null
            }
            val currentRefresh = tokenStore.refreshToken ?: return@Authenticator null
            synchronized(this) {
                try {
                    val refreshCall = syncRefreshApi.refreshSync(RefreshTokenRequest(currentRefresh))
                    val refreshResponse = refreshCall.execute()
                    val body = refreshResponse.body()?.data
                    if (refreshResponse.isSuccessful && body != null) {
                        tokenStore.saveSession(body.accessToken, body.refreshToken, body.user)
                        return@Authenticator response.request.newBuilder()
                            .header("Authorization", "Bearer ${body.accessToken}")
                            .build()
                    } else {
                        tokenStore.clearSession()
                        return@Authenticator null
                    }
                } catch (_: Exception) {
                    return@Authenticator null
                }
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(SightseeingApiService::class.java)
    }

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    /**
     * Compresses a captured photo file into JPEG (about 300 KB, preserving its aspect ratio)
     * and constructs multipart parts for `POST /api/v1/pois/{poiId}/photos`.
     */
    fun buildPhotoUploadMultipart(
        photoFile: File,
        capturedLatitude: Double,
        capturedLongitude: Double,
        azimuthDegrees: Float,
        pitchDegrees: Float,
        caption: String
    ): PhotoMultipartPayload {
        val compressedBytes = compressJpegAround300Kb(photoFile)
        val fileBody = compressedBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData(
            "file",
            "poi_capture_${System.currentTimeMillis()}.jpg",
            fileBody
        )
        val textPlain = "text/plain".toMediaType()
        return PhotoMultipartPayload(
            filePart = filePart,
            capturedLatitude = capturedLatitude.toString().toRequestBody(textPlain),
            capturedLongitude = capturedLongitude.toString().toRequestBody(textPlain),
            azimuthDegrees = azimuthDegrees.toString().toRequestBody(textPlain),
            pitchDegrees = pitchDegrees.toString().toRequestBody(textPlain),
            caption = caption.trim().toRequestBody(textPlain)
        )
    }

    fun buildPoiCoverUploadPart(photoFile: File): MultipartBody.Part {
        val compressedBytes = compressJpegAround300Kb(photoFile)
        val fileBody = compressedBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        return MultipartBody.Part.createFormData(
            "file",
            "poi_cover_${System.currentTimeMillis()}.jpg",
            fileBody
        )
    }

    private fun compressJpegAround300Kb(sourceFile: File): ByteArray {
        if (!sourceFile.isFile || !sourceFile.canRead()) {
            throw IOException("Không thể đọc ảnh vừa chụp.")
        }

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            throw IOException("Ảnh vừa chụp không hợp lệ hoặc không thể giải mã.")
        }

        var sampleSize = 1
        while (
            (boundsOptions.outWidth.toLong() / sampleSize) *
                (boundsOptions.outHeight.toLong() / sampleSize) > MAX_DECODED_PHOTO_PIXELS
        ) {
            sampleSize *= 2
        }

        var bitmap = BitmapFactory.decodeFile(
            sourceFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: throw IOException("Không thể giải mã ảnh vừa chụp.")
        val output = ByteArrayOutputStream()
        try {
            while (true) {
                for (quality in intArrayOf(88, 80, 72, 64, 56, 48, 40)) {
                    output.reset()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        throw IOException("Không thể nén ảnh vừa chụp.")
                    }
                    if (output.size() <= TARGET_PHOTO_BYTES) {
                        return output.toByteArray()
                    }
                }

                val sizeRatio = sqrt(TARGET_PHOTO_BYTES.toDouble() / output.size().toDouble())
                val scale = (sizeRatio * 0.9).toFloat().coerceIn(0.25f, 0.85f)
                val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
                    throw IOException("Không thể nén ảnh xuống khoảng 300 KB.")
                }

                val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (resized !== bitmap) bitmap.recycle()
                bitmap = resized
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            output.close()
        }
    }
}

data class PhotoMultipartPayload(
    val filePart: MultipartBody.Part,
    val capturedLatitude: RequestBody,
    val capturedLongitude: RequestBody,
    val azimuthDegrees: RequestBody,
    val pitchDegrees: RequestBody,
    val caption: RequestBody
)
