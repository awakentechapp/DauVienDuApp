namespace SightseeingTravel.Modules.Attractions.Domain;

public static class PoiCategories
{
    public const string FoodAndDrink = "food_and_drink";
    public const string TouristAttraction = "tourist_attraction";
    public const string CulturalHeritage = "cultural_heritage";
    public const string HistoricalSite = "historical_site";

    public static IReadOnlyList<string> All { get; } =
    [FoodAndDrink, TouristAttraction, CulturalHeritage, HistoricalSite];

    public static bool TryNormalize(string? value, out string normalized)
    {
        var key = value?.Trim().ToLowerInvariant();
        normalized = key switch
        {
            "food_and_drink" or "food" or "food & drink" or "food & dining" or "restaurant" or "ăn uống" or "ẩm thực" => FoodAndDrink,
            "tourist_attraction" or "attraction" or "sightseeing" or "tourist attraction" or "địa điểm tham quan" => TouristAttraction,
            "cultural_heritage" or "heritage" or "heritage landmark" or "architecture & heritage"
                or "cultural heritage" or "di sản văn hoá" or "di sản văn hóa" => CulturalHeritage,
            "historical_site" or "historic landmark" or "historical landmark" or "historical site"
                or "national monument" or "di tích lịch sử" => HistoricalSite,
            _ => string.Empty
        };

        return normalized.Length > 0;
    }
}
