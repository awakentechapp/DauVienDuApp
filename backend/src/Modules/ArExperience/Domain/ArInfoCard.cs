using SightseeingTravel.SharedKernel.Common;
using SightseeingTravel.SharedKernel.Domain;

namespace SightseeingTravel.Modules.ArExperience.Domain;

public enum ArCardType
{
    StoryHighlight = 1,
    HistoricalFact = 2,
    ArchitectureNote = 3,
    DirectionHint = 4
}

public enum ArModelCategory
{
    Food = 1,
    Object = 2
}

public sealed class ArInfoCard : AuditableEntity
{
    public const double MinAzimuthDegrees = 0.0;
    public const double MaxAzimuthDegrees = 360.0;
    public const double MinDistanceMeters = 1.0;
    public const double MaxDistanceMeters = 50.0;
    public const double MinElevationMeters = -5.0;
    public const double MaxElevationMeters = 20.0;

    public Guid PoiId { get; set; }
    public ArCardType CardType { get; set; } = ArCardType.StoryHighlight;
    public double AzimuthDegrees { get; set; }
    public double DistanceMeters { get; set; } = 5.0;
    public double ElevationMeters { get; set; }
    public int DisplayOrder { get; set; }
    public bool IsActive { get; set; } = true;
    public string? ModelObjectKey { get; private set; }
    public string? ModelName { get; private set; }
    public ArModelCategory ModelCategory { get; private set; } = ArModelCategory.Object;
    public string ModelStatus { get; private set; } = "None";
    public string? ModelError { get; private set; }
    public string? NodeOdmTaskId { get; private set; }
    public double ModelRotationDegrees { get; private set; }
    public List<ArInfoCardLocalization> Localizations { get; set; } = new();

    private ArInfoCard()
    {
    }

    private ArInfoCard(
        Guid id,
        Guid poiId,
        ArCardType cardType,
        double azimuthDegrees,
        double distanceMeters,
        double elevationMeters,
        int displayOrder,
        bool isActive,
        DateTimeOffset nowUtc)
    {
        Id = id;
        PoiId = poiId;
        CardType = cardType;
        AzimuthDegrees = azimuthDegrees;
        DistanceMeters = distanceMeters;
        ElevationMeters = elevationMeters;
        DisplayOrder = displayOrder;
        IsActive = isActive;
        CreatedAt = nowUtc;
        UpdatedAt = nowUtc;
    }

    public static Result<ArInfoCard> Create(
        Guid poiId,
        ArCardType cardType,
        double azimuthDegrees,
        double distanceMeters,
        double elevationMeters,
        int displayOrder,
        bool isActive,
        DateTimeOffset nowUtc)
    {
        if (poiId == Guid.Empty)
        {
            return Result.Failure<ArInfoCard>(new Error(
                "AR_CARD_INVALID_POI",
                "POI identifier is required.",
                ErrorType.Validation));
        }

        var placementValidation = ValidateSpatialPlacement(azimuthDegrees, distanceMeters, elevationMeters, displayOrder);
        if (placementValidation.IsFailure)
        {
            return Result.Failure<ArInfoCard>(placementValidation.Error);
        }

        var card = new ArInfoCard(
            Guid.NewGuid(),
            poiId,
            cardType,
            Math.Round(azimuthDegrees, 2),
            Math.Round(distanceMeters, 2),
            Math.Round(elevationMeters, 2),
            displayOrder,
            isActive,
            nowUtc);

        return Result.Success(card);
    }

    public Result Update(
        ArCardType cardType,
        double azimuthDegrees,
        double distanceMeters,
        double elevationMeters,
        int displayOrder,
        bool isActive,
        DateTimeOffset nowUtc)
    {
        var placementValidation = ValidateSpatialPlacement(azimuthDegrees, distanceMeters, elevationMeters, displayOrder);
        if (placementValidation.IsFailure)
        {
            return placementValidation;
        }

        CardType = cardType;
        AzimuthDegrees = Math.Round(azimuthDegrees, 2);
        DistanceMeters = Math.Round(distanceMeters, 2);
        ElevationMeters = Math.Round(elevationMeters, 2);
        DisplayOrder = displayOrder;
        IsActive = isActive;
        UpdatedAt = nowUtc;

        return Result.Success();
    }

    public Result ReplaceLocalizations(IEnumerable<(string LanguageCode, string Title, string? Subtitle, string Content)> items)
    {
        var list = items.ToList();
        if (list.Count == 0)
        {
            return Result.Failure(new Error(
                "AR_CARD_LOCALIZATION_REQUIRED",
                "At least one localization entry is required for an AR Info Card.",
                ErrorType.Validation));
        }

        var normalizedSet = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var newLocalizations = new List<ArInfoCardLocalization>(list.Count);

        foreach (var item in list)
        {
            if (string.IsNullOrWhiteSpace(item.LanguageCode))
            {
                return Result.Failure(new Error(
                    "AR_CARD_LANGUAGE_REQUIRED",
                    "Localization language code is required.",
                    ErrorType.Validation));
            }

            var normalizedLang = SupportedLanguages.Normalize(item.LanguageCode);
            if (!SupportedLanguages.All.Contains(normalizedLang, StringComparer.OrdinalIgnoreCase))
            {
                return Result.Failure(new Error(
                    "AR_CARD_UNSUPPORTED_LANGUAGE",
                    $"Language '{item.LanguageCode}' is not supported. Supported languages: {string.Join(", ", SupportedLanguages.All)}.",
                    ErrorType.Validation));
            }

            if (!normalizedSet.Add(normalizedLang))
            {
                return Result.Failure(new Error(
                    "AR_CARD_DUPLICATE_LANGUAGE",
                    $"Duplicate localization provided for language '{normalizedLang}'.",
                    ErrorType.Validation));
            }

            if (string.IsNullOrWhiteSpace(item.Title))
            {
                return Result.Failure(new Error(
                    "AR_CARD_TITLE_REQUIRED",
                    $"Title is required for language '{normalizedLang}'.",
                    ErrorType.Validation));
            }

            if (string.IsNullOrWhiteSpace(item.Content))
            {
                return Result.Failure(new Error(
                    "AR_CARD_CONTENT_REQUIRED",
                    $"Content is required for language '{normalizedLang}'.",
                    ErrorType.Validation));
            }

            newLocalizations.Add(ArInfoCardLocalization.Create(
                Id,
                normalizedLang,
                item.Title,
                item.Subtitle,
                item.Content));
        }

        Localizations.Clear();
        Localizations.AddRange(newLocalizations);
        return Result.Success();
    }

    public void BeginModelProcessing(
        string nodeOdmTaskId,
        string modelName,
        ArModelCategory modelCategory,
        DateTimeOffset nowUtc)
    {
        NodeOdmTaskId = nodeOdmTaskId;
        ModelName = modelName.Trim();
        ModelCategory = modelCategory;
        ModelStatus = "Processing";
        ModelError = null;
        UpdatedAt = nowUtc;
    }

    public void CompleteModelProcessing(string objectKey, DateTimeOffset nowUtc)
    {
        ModelObjectKey = objectKey;
        ModelStatus = "Ready";
        ModelError = null;
        NodeOdmTaskId = null;
        UpdatedAt = nowUtc;
    }

    public Result AttachExistingModel(
        string objectKey,
        double modelRotationDegrees,
        string? modelName,
        ArModelCategory modelCategory,
        DateTimeOffset nowUtc)
    {
        if (string.IsNullOrWhiteSpace(objectKey))
        {
            return Result.Failure(new Error(
                "AR_MODEL_OBJECT_REQUIRED",
                "A ready 3D model object is required.",
                ErrorType.Validation));
        }

        if (double.IsNaN(modelRotationDegrees) || double.IsInfinity(modelRotationDegrees))
        {
            return Result.Failure(new Error(
                "AR_MODEL_INVALID_ROTATION",
                "Model rotation must be a finite number of degrees.",
                ErrorType.Validation));
        }

        ModelObjectKey = objectKey;
        ModelName = string.IsNullOrWhiteSpace(modelName) ? null : modelName.Trim();
        ModelCategory = modelCategory;
        ModelRotationDegrees = ((modelRotationDegrees % 360.0) + 360.0) % 360.0;
        ModelStatus = "Ready";
        ModelError = null;
        NodeOdmTaskId = null;
        UpdatedAt = nowUtc;
        return Result.Success();
    }

    public void FailModelProcessing(string error, DateTimeOffset nowUtc)
    {
        ModelStatus = "Failed";
        ModelError = error.Length <= 500 ? error : error[..500];
        NodeOdmTaskId = null;
        UpdatedAt = nowUtc;
    }

    public static Result ValidateSpatialPlacement(
        double azimuthDegrees,
        double distanceMeters,
        double elevationMeters,
        int displayOrder)
    {
        if (double.IsNaN(azimuthDegrees) || double.IsInfinity(azimuthDegrees) ||
            azimuthDegrees < MinAzimuthDegrees || azimuthDegrees > MaxAzimuthDegrees)
        {
            return Result.Failure(new Error(
                "AR_CARD_INVALID_AZIMUTH",
                $"AzimuthDegrees must be between {MinAzimuthDegrees:F1} and {MaxAzimuthDegrees:F1}.",
                ErrorType.Validation));
        }

        if (double.IsNaN(distanceMeters) || double.IsInfinity(distanceMeters) ||
            distanceMeters < MinDistanceMeters || distanceMeters > MaxDistanceMeters)
        {
            return Result.Failure(new Error(
                "AR_CARD_INVALID_DISTANCE",
                $"DistanceMeters must be between {MinDistanceMeters:F1}m and {MaxDistanceMeters:F1}m.",
                ErrorType.Validation));
        }

        if (double.IsNaN(elevationMeters) || double.IsInfinity(elevationMeters) ||
            elevationMeters < MinElevationMeters || elevationMeters > MaxElevationMeters)
        {
            return Result.Failure(new Error(
                "AR_CARD_INVALID_ELEVATION",
                $"ElevationMeters must be between {MinElevationMeters:F1}m and {MaxElevationMeters:F1}m.",
                ErrorType.Validation));
        }

        if (displayOrder < 0)
        {
            return Result.Failure(new Error(
                "AR_CARD_INVALID_DISPLAY_ORDER",
                "DisplayOrder must be a non-negative integer.",
                ErrorType.Validation));
        }

        return Result.Success();
    }

    public static bool TryParseCardType(string? rawCardType, out ArCardType cardType)
    {
        if (string.IsNullOrWhiteSpace(rawCardType))
        {
            cardType = default;
            return false;
        }

        var normalized = rawCardType.Trim().Replace("_", string.Empty).Replace("-", string.Empty);
        if (Enum.TryParse(normalized, ignoreCase: true, out cardType) && Enum.IsDefined(cardType))
        {
            return true;
        }

        cardType = default;
        return false;
    }
}
