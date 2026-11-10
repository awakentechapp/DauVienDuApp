package com.awaken.sightseeingtravel.core.network

import com.awaken.sightseeingtravel.core.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** Converts the backend's raw DTOs and `{ items: [...] }` list responses into the app's envelope. */
internal object ApiResponseAdapters {
    fun createGson(): Gson = GsonBuilder()
        .registerTypeAdapter(ApiEnvelope::class.java, ApiEnvelopeDeserializer())
        .registerTypeAdapter(PoiPinSummaryDto::class.java, PoiPinDeserializer())
        .registerTypeAdapter(PoiDetailDto::class.java, PoiDetailDeserializer())
        .registerTypeAdapter(ArInfoCardDto::class.java, ArInfoCardDeserializer())
        .registerTypeAdapter(PoiArContextDto::class.java, PoiArContextDeserializer())
        .registerTypeAdapter(CommunityPhotoDto::class.java, CommunityPhotoDeserializer())
        .registerTypeAdapter(LikePhotoResponseDto::class.java, LikePhotoDeserializer())
        .registerTypeAdapter(AdminPoiDto::class.java, AdminPoiDeserializer())
        .registerTypeAdapter(ModerationPhotoItemDto::class.java, ModerationPhotoDeserializer())
        .create()

    private class ApiEnvelopeDeserializer : JsonDeserializer<ApiEnvelope<*>> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ApiEnvelope<*> {
            val root = json.asObjectOrNull()
            val payloadType = (typeOfT as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
                ?: Any::class.java
            val wrapped = root?.has("success") == true && root.has("data")
            var payload = if (wrapped) root!!["data"] else json

            if (payloadType.isListType() && payload.isJsonObject && payload.asJsonObject.has("items")) {
                payload = payload.asJsonObject["items"]
            }

            val success = if (wrapped) root!!["success"]?.takeUnless { it.isJsonNull }?.asBoolean ?: true else true
            val metaJson = root?.get("meta") ?: payload.asObjectOrNull()?.takeIf { it.has("page") }
            val errorJson = root?.get("error")
            val meta = metaJson?.takeUnless { it.isJsonNull }
                ?.let { context.deserialize<PaginationMeta>(it, PaginationMeta::class.java) }
            val error = errorJson?.takeUnless { it.isJsonNull }
                ?.let { context.deserialize<ApiErrorDto>(it, ApiErrorDto::class.java) }
            val data = if (payload.isJsonNull || payloadType.isUnitType()) {
                null
            } else {
                context.deserialize<Any>(payload, payloadType)
            }

            return ApiEnvelope(success = success, data = data, meta = meta, error = error)
        }
    }

    private class PoiPinDeserializer : JsonDeserializer<PoiPinSummaryDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): PoiPinSummaryDto {
            val value = json.asObjectOrThrow(typeOfT)
            return PoiPinSummaryDto(
                id = value.string("id"),
                slug = value.string("code", "slug"),
                category = value.string("category", default = "Heritage"),
                latitude = value.double("latitude"),
                longitude = value.double("longitude"),
                elevationMeters = value.double("elevationMeters"),
                activationRadiusMeters = value.double("activationRadiusMeters", default = 50.0),
                distanceMeters = value.nullableDouble("distanceMeters"),
                title = value.string("name", "title"),
                shortSummary = value.string("shortSummary"),
                coverImageUrl = value.nullableString("coverImageUrl"),
                audioGuideUrl = value.nullableString("audioGuideUrl"),
                status = value.string("status", default = "Published"),
                communityPhotoCount = value.int("communityPhotoCount", "communityPhotosCount"),
                arInfoCardCount = value.int("arInfoCardCount")
            )
        }
    }

    private class PoiDetailDeserializer : JsonDeserializer<PoiDetailDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): PoiDetailDto {
            val value = json.asObjectOrThrow(typeOfT)
            val canActivate = value.nullableBoolean("canActivateAr", "isArUnlocked") ?: false
            return PoiDetailDto(
                id = value.string("id"),
                slug = value.string("code", "slug"),
                category = value.string("category", default = "Heritage"),
                latitude = value.double("latitude"),
                longitude = value.double("longitude"),
                elevationMeters = value.double("elevationMeters"),
                activationRadiusMeters = value.double("activationRadiusMeters", default = 50.0),
                distanceMeters = value.nullableDouble("distanceMeters"),
                isArUnlocked = canActivate,
                languageCode = value.string("languageCode", default = "vi"),
                title = value.string("name", "title"),
                shortSummary = value.string("shortSummary"),
                culturalStoryMarkdown = value.string("culturalStoryMarkdown"),
                historicalPeriod = value.nullableString("historicalPeriod"),
                architecturalHighlights = emptyList(),
                visitorTips = value.nullableString("visitorTips"),
                coverImageUrl = value.nullableString("coverImageUrl"),
                audioGuideUrl = value.nullableString("audioGuideUrl"),
                arInfoCards = value.array("arInfoCards", context),
                highlightedCommunityPhotos = value.array("highlightedCommunityPhotos", context),
                status = value.string("status", default = "Published"),
                rejectionReason = value.nullableString("rejectionReason")
            )
        }
    }

    private class ArInfoCardDeserializer : JsonDeserializer<ArInfoCardDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ArInfoCardDto {
            val value = json.asObjectOrThrow(typeOfT)
            return ArInfoCardDto(
                id = value.string("id"),
                poiId = value.string("poiId"),
                title = value.string("title"),
                subtitle = value.string("subtitle"),
                bodyText = value.string("content", "bodyText"),
                badgeLabel = value.string("badgeLabel", default = "Cultural Insight"),
                mediaUrl = value.nullableString("mediaUrl"),
                audioSnippetUrl = value.nullableString("audioSnippetUrl"),
                azimuthDegrees = value.double("azimuthDegrees").toFloat(),
                elevationMeters = value.double("elevationMeters", default = 2.0).toFloat(),
                anchorDistanceMeters = value.double("distanceMeters", "anchorDistanceMeters", default = 8.0).toFloat(),
                displayOrder = value.int("displayOrder")
            )
        }
    }

    private class PoiArContextDeserializer : JsonDeserializer<PoiArContextDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): PoiArContextDto {
            val value = json.asObjectOrThrow(typeOfT)
            return PoiArContextDto(
                poiId = value.string("poiId"),
                title = value.string("poiName", "title"),
                category = value.string("category", default = "Heritage"),
                latitude = value.double("poiLatitude", "latitude"),
                longitude = value.double("poiLongitude", "longitude"),
                activationRadiusMeters = value.double("activationRadiusMeters", default = 50.0),
                distanceMeters = value.double("userDistanceMeters", "distanceMeters"),
                isArUnlocked = value.nullableBoolean("isArUnlocked") ?: true,
                arInfoCards = value.array("arInfoCards", context),
                highlightedCommunityPhotos = value.array("highlightedCommunityPhotos", context)
            )
        }
    }

    private class CommunityPhotoDeserializer : JsonDeserializer<CommunityPhotoDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): CommunityPhotoDto {
            val value = json.asObjectOrThrow(typeOfT)
            return CommunityPhotoDto(
                id = value.string("id"),
                poiId = value.string("poiId"),
                authorId = value.string("userId", "authorId"),
                authorDisplayName = value.string("authorDisplayName"),
                authorAvatarUrl = value.nullableString("authorAvatarUrl"),
                imageUrl = value.string("imageUrl"),
                thumbnailUrl = value.nullableString("thumbnailUrl"),
                caption = value.nullableString("caption"),
                capturedLatitude = value.double("capturedLatitude"),
                capturedLongitude = value.double("capturedLongitude"),
                azimuthDegrees = value.nullableDouble("azimuthDegrees")?.toFloat(),
                elevationMeters = value.double("elevationMeters", default = 1.5).toFloat(),
                likeCount = value.int("likeCount", "likesCount"),
                isLikedByCurrentUser = value.nullableBoolean("isLikedByCurrentUser") ?: false,
                isHighlightedInAr = value.nullableBoolean("isHighlightedInAr") ?: false,
                highlightRank = value.nullableInt("highlightRank"),
                createdAt = value.string("createdAt")
            )
        }
    }

    private class LikePhotoDeserializer : JsonDeserializer<LikePhotoResponseDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LikePhotoResponseDto {
            val value = json.asObjectOrThrow(typeOfT)
            return LikePhotoResponseDto(
                photoId = value.string("photoId"),
                likeCount = value.int("likeCount", "likesCount"),
                isLikedByCurrentUser = value.nullableBoolean("isLiked", "isLikedByCurrentUser") ?: false
            )
        }
    }

    private class AdminPoiDeserializer : JsonDeserializer<AdminPoiDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): AdminPoiDto {
            val value = json.asObjectOrThrow(typeOfT)
            val localizations = value["localizations"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.asObjectOrNull() }
                .orEmpty()
            val vi = localizations.firstOrNull { it.string("languageCode").equals("vi", true) }
            val en = localizations.firstOrNull { it.string("languageCode").equals("en", true) }
            val fallback = localizations.firstOrNull()
            return AdminPoiDto(
                id = value.string("id"),
                slug = value.string("code", "slug"),
                category = value.string("category", default = "Heritage"),
                latitude = value.double("latitude"),
                longitude = value.double("longitude"),
                activationRadiusMeters = value.double("activationRadiusMeters", default = 50.0),
                status = value.string("status", default = "Draft"),
                rejectionReason = value.nullableString("rejectionReason"),
                createdByAdminName = value.string("createdByAdminName", "createdBy", default = "Admin"),
                approvedBySuperAdminName = value.nullableString("approvedBySuperAdminName", "approvedBy"),
                updatedAt = value.string("updatedAt", "createdAt"),
                titleVi = vi?.string("name") ?: fallback?.string("name").orEmpty(),
                titleEn = en?.string("name") ?: fallback?.string("name").orEmpty(),
                shortSummaryVi = vi?.string("shortSummary").orEmpty(),
                shortSummaryEn = en?.string("shortSummary").orEmpty(),
                arInfoCardCount = value.int("arInfoCardCount")
            )
        }
    }

    private class ModerationPhotoDeserializer : JsonDeserializer<ModerationPhotoItemDto> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ModerationPhotoItemDto {
            val value = json.asObjectOrThrow(typeOfT)
            val reports = value["reports"]?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.asObjectOrNull() }
                .orEmpty()
            val latestReason = reports.lastOrNull()?.string("reason")
            val photoId = value.string("photoId")
            return ModerationPhotoItemDto(
                photoId = photoId,
                poiId = value.string("poiId"),
                poiTitle = value.string("poiTitle", "poiId"),
                imageUrl = value.string("imageUrl"),
                caption = value.nullableString("caption"),
                authorDisplayName = value.string("authorDisplayName"),
                reportCount = value.int("reportCount", "reportsCount", default = reports.size),
                latestReportReason = value.string("latestReportReason", default = latestReason ?: "Reported"),
                moderationStatus = value.string("status", "moderationStatus", default = "Flagged"),
                createdAt = value.string("createdAt")
            )
        }
    }

}

private fun JsonElement.asObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun JsonElement.asObjectOrThrow(type: Type): JsonObject =
    asObjectOrNull() ?: throw JsonParseException("Expected JSON object for $type")

private fun JsonObject.string(vararg keys: String, default: String = ""): String =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asString } ?: default

private fun JsonObject.nullableString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asString }

private fun JsonObject.double(vararg keys: String, default: Double = 0.0): Double =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asDouble } ?: default

private fun JsonObject.nullableDouble(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asDouble }

private fun JsonObject.int(vararg keys: String, default: Int = 0): Int =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asInt } ?: default

private fun JsonObject.nullableInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asInt }

private fun JsonObject.nullableBoolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull }?.asBoolean }

private inline fun <reified T> JsonObject.array(key: String, context: JsonDeserializationContext): List<T> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
        if (it.isJsonNull) null else context.deserialize<T>(it, T::class.java)
    }.orEmpty()

private fun Type.isListType(): Boolean =
    this is ParameterizedType && (rawType == List::class.java || rawType == MutableList::class.java)

private fun Type.isUnitType(): Boolean =
    this == Unit::class.java || this.typeName == "kotlin.Unit"
