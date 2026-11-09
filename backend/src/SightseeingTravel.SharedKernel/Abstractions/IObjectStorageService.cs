namespace SightseeingTravel.SharedKernel.Abstractions;

public sealed record StorageUploadResult(string ObjectKey, string StorageUrl, long SizeBytes, string ContentType);

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
