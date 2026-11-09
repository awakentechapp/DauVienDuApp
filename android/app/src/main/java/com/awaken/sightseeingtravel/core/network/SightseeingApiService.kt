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
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

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
    suspend fun logout(@Body request: LogoutRequest): ApiEnvelope<Unit>

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
        @Query("radiusKm") radiusKm: Double = 10.0,
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
    ): ApiEnvelope<DeletePhotoResponseDto>

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

    /**
     * SuperAdmin ONLY: Approves a `PendingApproval` POI and sets status to `Published`
     */
    @POST("api/v1/admin/pois/{poiId}/approve")
    suspend fun approvePoi(
        @Path("poiId") poiId: String,
        @Body request: ApprovePoiRequest = ApprovePoiRequest()
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

    private const val MAX_PHOTO_DIMENSION_PX = 1920
    private const val MAX_PHOTO_BYTES = 5 * 1024 * 1024 // 5 MB

    fun createApiService(tokenStore: TokenStore): SightseeingApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }

        val refreshRetrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
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
            .addConverterFactory(GsonConverterFactory.create())
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
     * Compresses a captured photo file into JPEG (`<= 1920px` longest edge and `<= 5MB`)
     * and constructs multipart parts for `POST /api/v1/pois/{poiId}/photos`.
     */
    fun buildPhotoUploadMultipart(
        photoFile: File,
        capturedLatitude: Double,
        capturedLongitude: Double,
        azimuthDegrees: Float,
        caption: String
    ): PhotoMultipartPayload {
        val compressedBytes = compressJpegWithinLimits(photoFile)
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
            caption = caption.trim().toRequestBody(textPlain)
        )
    }

    private fun compressJpegWithinLimits(sourceFile: File): ByteArray {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)

        var sampleSize = 1
        val maxSourceSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
        if (maxSourceSide > MAX_PHOTO_DIMENSION_PX) {
            sampleSize = max(1, (maxSourceSide.toFloat() / MAX_PHOTO_DIMENSION_PX).roundToInt())
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
            ?: return sourceFile.readBytes()

        val longestSide = max(decoded.width, decoded.height)
        val scaledBitmap = if (longestSide > MAX_PHOTO_DIMENSION_PX) {
            val ratio = MAX_PHOTO_DIMENSION_PX.toFloat() / longestSide.toFloat()
            val targetW = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
            val targetH = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        } else {
            decoded
        }

        var quality = 88
        val output = ByteArrayOutputStream()
        do {
            output.reset()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            quality -= 8
        } while (output.size() > MAX_PHOTO_BYTES && quality >= 40)

        return output.toByteArray()
    }
}

data class PhotoMultipartPayload(
    val filePart: MultipartBody.Part,
    val capturedLatitude: RequestBody,
    val capturedLongitude: RequestBody,
    val azimuthDegrees: RequestBody,
    val caption: RequestBody
)
