package com.awaken.sightseeingtravel.core.model

object PoiCategoryLabels {
    const val FOOD_AND_DRINK = "food_and_drink"
    const val TOURIST_ATTRACTION = "tourist_attraction"
    const val CULTURAL_HERITAGE = "cultural_heritage"
    const val HISTORICAL_SITE = "historical_site"

    val values = listOf(FOOD_AND_DRINK, TOURIST_ATTRACTION, CULTURAL_HERITAGE, HISTORICAL_SITE)

    fun normalize(value: String?): String = when (value?.trim()?.lowercase()) {
        "food_and_drink", "food", "food & drink", "food & dining", "restaurant", "ăn uống", "ẩm thực" -> FOOD_AND_DRINK
        "tourist_attraction", "attraction", "sightseeing", "tourist attraction", "địa điểm tham quan" -> TOURIST_ATTRACTION
        "cultural_heritage", "heritage", "heritage landmark", "architecture & heritage",
        "cultural heritage", "di sản văn hoá", "di sản văn hóa" -> CULTURAL_HERITAGE
        "historical_site", "historic landmark", "historical landmark", "historical site", "national monument",
        "di tích lịch sử" -> HISTORICAL_SITE
        else -> HISTORICAL_SITE
    }

    fun isFood(value: String): Boolean = normalize(value) == FOOD_AND_DRINK

    fun label(value: String, languageCode: String): String {
        val isEnglish = languageCode.equals("en", ignoreCase = true)
        return when (normalize(value)) {
            FOOD_AND_DRINK -> if (isEnglish) "Food & Drink" else "Ăn uống"
            TOURIST_ATTRACTION -> if (isEnglish) "Tourist Attraction" else "Địa điểm tham quan"
            CULTURAL_HERITAGE -> if (isEnglish) "Cultural Heritage" else "Di sản văn hoá"
            else -> if (isEnglish) "Historical Site" else "Di tích lịch sử"
        }
    }
}
