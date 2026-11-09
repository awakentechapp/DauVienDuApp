namespace SightseeingTravel.SharedKernel.Abstractions;

public sealed record StorageUploadResult(string ObjectKey, string PublicUrl, long SizeBytes, string ContentType);

public interface IObjectStorageService
{
    Task<StorageUploadResult> UploadPhotoAsync(
        Stream fileStream,
        string contentType,
        string objectKey,
        CancellationToken cancellationToken = default);

    Task DeleteObjectAsync(
        string objectKey,
        CancellationToken cancellationToken = default);
}
