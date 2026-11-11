namespace SightseeingTravel.SharedKernel.Abstractions;

public sealed record StorageUploadResult(string ObjectKey, string StorageUrl, long SizeBytes, string ContentType);

public sealed class ObjectStorageUploadException : Exception
{
    public ObjectStorageUploadException(
        string message,
        Exception innerException,
        string? storageErrorCode = null)
        : base(message, innerException)
    {
        StorageErrorCode = storageErrorCode;
    }

    public string? StorageErrorCode { get; }
}

public interface IObjectStorageService
{
    Task<StorageUploadResult> UploadPhotoAsync(
        Stream fileStream,
        string contentType,
        string objectKey,
        CancellationToken cancellationToken = default);

    string CreateReadUrl(string objectKey);

    Task DeleteObjectAsync(
        string objectKey,
        CancellationToken cancellationToken = default);
}
