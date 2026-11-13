namespace SightseeingTravel.Modules.ArExperience.Domain;

/// <summary>A reusable 3D object, independent of any POI or AR card.</summary>
public sealed class ArModelAsset
{
    public Guid Id { get; private set; }
    public string Name { get; private set; } = string.Empty;
    public ArModelCategory Category { get; private set; } = ArModelCategory.Object;
    public string? ModelObjectKey { get; private set; }
    public string ModelStatus { get; private set; } = "Processing";
    public string? ModelError { get; private set; }
    public string? NodeOdmTaskId { get; private set; }
    public double ModelRotationDegrees { get; private set; }
    public DateTimeOffset CreatedAt { get; private set; }
    public DateTimeOffset UpdatedAt { get; private set; }

    private ArModelAsset() { }

    public static ArModelAsset Create(
        Guid id,
        string name,
        ArModelCategory category,
        string nodeOdmTaskId,
        DateTimeOffset nowUtc)
    {
        if (id == Guid.Empty) throw new ArgumentException("A model asset id is required.", nameof(id));
        if (string.IsNullOrWhiteSpace(name) || name.Trim().Length > 200)
            throw new ArgumentException("A model name of 1 to 200 characters is required.", nameof(name));
        if (string.IsNullOrWhiteSpace(nodeOdmTaskId))
            throw new ArgumentException("A processing task id is required.", nameof(nodeOdmTaskId));

        return new ArModelAsset
        {
            Id = id,
            Name = name.Trim(),
            Category = category,
            NodeOdmTaskId = nodeOdmTaskId,
            ModelStatus = "Processing",
            CreatedAt = nowUtc,
            UpdatedAt = nowUtc
        };
    }

    public void CompleteModelProcessing(string objectKey, DateTimeOffset nowUtc)
    {
        ModelObjectKey = objectKey;
        ModelStatus = "Ready";
        ModelError = null;
        NodeOdmTaskId = null;
        UpdatedAt = nowUtc;
    }

    public void FailModelProcessing(string error, DateTimeOffset nowUtc)
    {
        ModelStatus = "Failed";
        ModelError = error.Length <= 500 ? error : error[..500];
        NodeOdmTaskId = null;
        UpdatedAt = nowUtc;
    }
}
