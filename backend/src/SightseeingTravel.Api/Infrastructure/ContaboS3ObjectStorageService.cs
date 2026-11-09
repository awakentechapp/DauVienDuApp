using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;
using Microsoft.Extensions.Options;
using SightseeingTravel.SharedKernel.Abstractions;

namespace SightseeingTravel.Api.Infrastructure;

public sealed class ContaboStorageOptions
{
    public const string SectionName = "ContaboStorage";

    public string ServiceUrl { get; set; } = "https://sin1.contabostorage.com";
    public string BucketName { get; set; } = "awakencloud";
    public string PublicBaseUrl { get; set; } = "https://sin1.contabostorage.com/3e270ee749cc4c6caa56fcceac2c4202:awakencloud";
    public string KeyPrefix { get; set; } = "dauviendu_cloud";
    public string AccessKey { get; set; } = string.Empty;
    public string SecretKey { get; set; } = string.Empty;
    public bool ForcePathStyle { get; set; } = true;
}

/// <summary>
/// S3-compatible Object Storage implementation configured specifically for Contabo Object Storage
/// with ForcePathStyle = true and custom ServiceURL.
/// </summary>
public sealed class ContaboS3ObjectStorageService : IObjectStorageService, IDisposable
{
    private static readonly TimeSpan ReadUrlLifetime = TimeSpan.FromMinutes(15);

    private readonly ContaboStorageOptions _options;
    private readonly IAmazonS3 _s3Client;

    public ContaboS3ObjectStorageService(IOptions<ContaboStorageOptions> options)
    {
        _options = options.Value;

        var s3Config = new AmazonS3Config
        {
            ServiceURL = _options.ServiceUrl,
            ForcePathStyle = _options.ForcePathStyle,
            UseHttp = _options.ServiceUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
        };

        var credentials = new BasicAWSCredentials(_options.AccessKey, _options.SecretKey);
        _s3Client = new AmazonS3Client(credentials, s3Config);
    }

    public async Task<StorageUploadResult> UploadPhotoAsync(
        Stream fileStream,
        string contentType,
        string objectKey,
        CancellationToken cancellationToken = default)
    {
        var normalizedKey = AddKeyPrefix(objectKey);
        if (fileStream.CanSeek)
            fileStream.Position = 0;

        var putRequest = new PutObjectRequest
        {
            BucketName = _options.BucketName,
            Key = normalizedKey,
            InputStream = fileStream,
            ContentType = contentType,
            AutoCloseStream = false
        };

        await _s3Client.PutObjectAsync(putRequest, cancellationToken);

        var storageUrl = $"{_options.PublicBaseUrl.TrimEnd('/')}/{normalizedKey}";
        var length = fileStream.CanSeek ? fileStream.Length : 0L;

        return new StorageUploadResult(normalizedKey, storageUrl, length, contentType);
    }

    public string CreateReadUrl(string objectKey)
    {
        if (string.IsNullOrWhiteSpace(objectKey))
        {
            throw new ArgumentException("An object key is required.", nameof(objectKey));
        }

        var request = new GetPreSignedUrlRequest
        {
            BucketName = _options.BucketName,
            Key = objectKey.TrimStart('/'),
            Verb = HttpVerb.GET,
            Expires = DateTime.UtcNow.Add(ReadUrlLifetime)
        };

        return _s3Client.GetPreSignedURL(request);
    }

    public async Task DeleteObjectAsync(
        string objectKey,
        CancellationToken cancellationToken = default)
    {
        var normalizedKey = objectKey.TrimStart('/');
        var deleteRequest = new DeleteObjectRequest
        {
            BucketName = _options.BucketName,
            Key = normalizedKey
        };

        await _s3Client.DeleteObjectAsync(deleteRequest, cancellationToken);
    }

    private string AddKeyPrefix(string objectKey)
    {
        var normalizedKey = objectKey.TrimStart('/');
        var prefix = _options.KeyPrefix.Trim('/');

        if (string.IsNullOrWhiteSpace(prefix) ||
            normalizedKey.Equals(prefix, StringComparison.Ordinal) ||
            normalizedKey.StartsWith($"{prefix}/", StringComparison.Ordinal))
        {
            return normalizedKey;
        }

        return $"{prefix}/{normalizedKey}";
    }

    public void Dispose()
    {
        _s3Client.Dispose();
    }
}
