using System.IO.Compression;
using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using SightseeingTravel.Modules.ArExperience.Domain;
using SightseeingTravel.Modules.ArExperience.Infrastructure;
using SightseeingTravel.SharedKernel.Abstractions;

namespace SightseeingTravel.Api.Infrastructure;

public sealed class ArModelProcessingOptions
{
    public const string SectionName = "ArModelProcessing";

    public bool Enabled { get; set; } = true;
    public string NodeOdmUrl { get; set; } = "http://127.0.0.1:3000";
    public string? NodeOdmToken { get; set; }
    public int MinImages { get; set; } = 12;
    public int MaxImages { get; set; } = 32;
    public long MaxUploadBytes { get; set; } = 120L * 1024 * 1024;
    public long MaxModelBytes { get; set; } = 200L * 1024 * 1024;
    public int PollIntervalSeconds { get; set; } = 15;
}

public sealed record ArModelBuildStatusResponse(
    Guid CardId,
    string Status,
    string? ErrorMessage,
    string? ModelName = null,
    string ModelCategory = "Object");

public sealed record ArModelBuildSubmissionResult(
    bool Success,
    int StatusCode,
    string Message,
    string Status = "None");

public interface IArModelTaskSubmissionService
{
    Task<ArModelBuildSubmissionResult> SubmitAsync(
        Guid cardId,
        IReadOnlyList<IFormFile> images,
        string modelName,
        string modelCategory,
        CancellationToken cancellationToken);
}

public sealed class ArModelTaskSubmissionService : IArModelTaskSubmissionService
{
    private readonly ArExperienceDbContext _dbContext;
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ArModelProcessingOptions _options;
    private readonly ILogger<ArModelTaskSubmissionService> _logger;

    public ArModelTaskSubmissionService(
        ArExperienceDbContext dbContext,
        IHttpClientFactory httpClientFactory,
        IOptions<ArModelProcessingOptions> options,
        ILogger<ArModelTaskSubmissionService> logger)
    {
        _dbContext = dbContext;
        _httpClientFactory = httpClientFactory;
        _options = options.Value;
        _logger = logger;
    }

    public async Task<ArModelBuildSubmissionResult> SubmitAsync(
        Guid cardId,
        IReadOnlyList<IFormFile> images,
        string modelName,
        string modelCategory,
        CancellationToken cancellationToken)
    {
        if (!_options.Enabled)
            return Failure(StatusCodes.Status503ServiceUnavailable, "Dịch vụ dựng mô hình 3D chưa được bật.");

        var requestedModelName = modelName.Trim();
        if (requestedModelName.Length > 200)
            return Failure(StatusCodes.Status400BadRequest, "Tên vật thể không được vượt quá 200 ký tự.");

        var requestedCategory = modelCategory.Trim();
        var parsedModelCategory = requestedCategory.Length == 0
            ? ArModelCategory.Object
            : requestedCategory.ToLowerInvariant() switch
        {
            "food" => ArModelCategory.Food,
            "object" => ArModelCategory.Object,
            _ => (ArModelCategory?)null
        };
        if (parsedModelCategory is null)
            return Failure(StatusCodes.Status400BadRequest, "Phân loại vật thể không hợp lệ.");

        if (images.Count < _options.MinImages || images.Count > _options.MaxImages)
            return Failure(StatusCodes.Status400BadRequest,
                $"Cần chụp từ {_options.MinImages} đến {_options.MaxImages} ảnh cho một mô hình 3D.");

        if (images.Any(image => image.Length <= 0 ||
                                !(image.ContentType.Equals("image/jpeg", StringComparison.OrdinalIgnoreCase) ||
                                  image.ContentType.Equals("image/png", StringComparison.OrdinalIgnoreCase))))
            return Failure(StatusCodes.Status400BadRequest, "Ảnh đầu vào phải là JPEG hoặc PNG hợp lệ.");

        var totalBytes = images.Sum(image => image.Length);
        if (totalBytes > _options.MaxUploadBytes)
            return Failure(StatusCodes.Status413PayloadTooLarge, "Tổng dung lượng ảnh vượt giới hạn cho phép.");

        var card = await _dbContext.ArInfoCards
            .Include(x => x.Localizations)
            .FirstOrDefaultAsync(x => x.Id == cardId, cancellationToken);
        if (card is null)
            return Failure(StatusCodes.Status404NotFound, "Không tìm thấy thẻ AR.");

        var normalizedModelName = requestedModelName.Length > 0
            ? requestedModelName
            : card.Localizations
                .OrderByDescending(x => x.LanguageCode.Equals("vi", StringComparison.OrdinalIgnoreCase))
                .ThenBy(x => x.LanguageCode)
                .Select(x => x.Title)
                .FirstOrDefault() ?? "Vật thể 3D";

        if (card.ModelStatus.Equals("Processing", StringComparison.OrdinalIgnoreCase))
            return Failure(StatusCodes.Status409Conflict, "Thẻ AR này đang được dựng mô hình 3D.");

        using var multipart = new MultipartFormDataContent();
        foreach (var image in images)
        {
            var streamContent = new StreamContent(image.OpenReadStream());
            streamContent.Headers.ContentType = new MediaTypeHeaderValue(image.ContentType);
            multipart.Add(streamContent, "images", Path.GetFileName(image.FileName));
        }

        multipart.Add(new StringContent($"ar-card-{cardId:N}"), "name");
        multipart.Add(new StringContent(
            "[{\"name\":\"gltf\",\"value\":true},{\"name\":\"bg-removal\",\"value\":true}]"),
            "options");
        multipart.Add(new StringContent("[\"odm_texturing/odm_textured_model_geo.glb\"]"), "outputs");

        var client = _httpClientFactory.CreateClient("NodeOdm");
        HttpResponseMessage response;
        try
        {
            response = await client.PostAsync(BuildNodeOdmUri("task/new"), multipart, cancellationToken);
        }
        catch (HttpRequestException exception)
        {
            _logger.LogWarning(exception, "NodeODM is unreachable while submitting AR card {CardId}.", cardId);
            return Failure(StatusCodes.Status503ServiceUnavailable, "Worker dựng mô hình 3D hiện chưa kết nối được.");
        }
        catch (OperationCanceledException exception) when (!cancellationToken.IsCancellationRequested)
        {
            _logger.LogWarning(exception, "NodeODM timed out while submitting AR card {CardId}.", cardId);
            return Failure(StatusCodes.Status503ServiceUnavailable, "Worker dựng mô hình 3D phản hồi quá thời gian.");
        }

        using (response)
        {
            if (!response.IsSuccessStatusCode)
            {
                var nodeError = await response.Content.ReadAsStringAsync(cancellationToken);
                _logger.LogWarning("NodeODM rejected an AR model task. HTTP {StatusCode}; response: {Response}",
                    (int)response.StatusCode, nodeError.Length > 500 ? nodeError[..500] : nodeError);
                return Failure(StatusCodes.Status502BadGateway, "Worker dựng mô hình 3D không nhận được ảnh.");
            }

            string? taskId;
            try
            {
                using var responseJson = await JsonDocument.ParseAsync(
                    await response.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
                taskId = responseJson.RootElement.TryGetProperty("uuid", out var uuidElement)
                    ? uuidElement.GetString()
                    : null;
            }
            catch (JsonException exception)
            {
                _logger.LogWarning(exception, "NodeODM returned invalid task metadata for AR card {CardId}.", cardId);
                return Failure(StatusCodes.Status502BadGateway, "Worker trả về trạng thái tác vụ không hợp lệ.");
            }
            if (string.IsNullOrWhiteSpace(taskId))
                return Failure(StatusCodes.Status502BadGateway, "Worker không trả về mã tác vụ dựng mô hình.");

            card.BeginModelProcessing(taskId, normalizedModelName, parsedModelCategory.Value, DateTimeOffset.UtcNow);
            await _dbContext.SaveChangesAsync(cancellationToken);

            return new ArModelBuildSubmissionResult(
                true,
                StatusCodes.Status202Accepted,
                "Đã nhận ảnh. Mô hình 3D đang được dựng trên máy chủ.",
                "Processing");
        }
    }

    private Uri BuildNodeOdmUri(string path)
    {
        var uri = new Uri(new Uri(_options.NodeOdmUrl.TrimEnd('/') + "/"), path);
        if (string.IsNullOrWhiteSpace(_options.NodeOdmToken))
            return uri;

        var builder = new UriBuilder(uri)
        {
            Query = $"token={Uri.EscapeDataString(_options.NodeOdmToken)}"
        };
        return builder.Uri;
    }

    private static ArModelBuildSubmissionResult Failure(int statusCode, string message) =>
        new(false, statusCode, message);
}

public sealed class ArModelProcessingWorker : BackgroundService
{
    private const string ExpectedModelPath = "odm_texturing/odm_textured_model_geo.glb";
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly IObjectStorageService _objectStorageService;
    private readonly ArModelProcessingOptions _options;
    private readonly ILogger<ArModelProcessingWorker> _logger;

    public ArModelProcessingWorker(
        IServiceScopeFactory scopeFactory,
        IHttpClientFactory httpClientFactory,
        IObjectStorageService objectStorageService,
        IOptions<ArModelProcessingOptions> options,
        ILogger<ArModelProcessingWorker> logger)
    {
        _scopeFactory = scopeFactory;
        _httpClientFactory = httpClientFactory;
        _objectStorageService = objectStorageService;
        _options = options.Value;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!_options.Enabled)
        {
            _logger.LogInformation("AR 3D model processing worker is disabled.");
            return;
        }

        var interval = TimeSpan.FromSeconds(Math.Clamp(_options.PollIntervalSeconds, 5, 120));
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await ProcessPendingTasksAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                _logger.LogError(exception, "Error while checking AR 3D model tasks.");
            }

            await Task.Delay(interval, stoppingToken);
        }
    }

    private async Task ProcessPendingTasksAsync(CancellationToken cancellationToken)
    {
        using var scope = _scopeFactory.CreateScope();
        var dbContext = scope.ServiceProvider.GetRequiredService<ArExperienceDbContext>();
        var tasks = await dbContext.ArInfoCards
            .AsNoTracking()
            .Where(card => card.ModelStatus == "Processing" && card.NodeOdmTaskId != null)
            .Select(card => new { card.Id, TaskId = card.NodeOdmTaskId! })
            .Take(4)
            .ToListAsync(cancellationToken);

        foreach (var task in tasks)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await CheckTaskAsync(dbContext, task.Id, task.TaskId, cancellationToken);
        }
    }

    private async Task CheckTaskAsync(
        ArExperienceDbContext dbContext,
        Guid cardId,
        string taskId,
        CancellationToken cancellationToken)
    {
        var client = _httpClientFactory.CreateClient("NodeOdm");
        using var statusResponse = await client.GetAsync(BuildNodeOdmUri($"task/{taskId}/info"), cancellationToken);
        if (statusResponse.StatusCode == System.Net.HttpStatusCode.NotFound)
        {
            await MarkFailedAsync(dbContext, cardId, taskId, "Worker không còn tìm thấy tác vụ dựng mô hình.", cancellationToken);
            return;
        }

        if (!statusResponse.IsSuccessStatusCode)
            return;

        using var statusJson = await JsonDocument.ParseAsync(
            await statusResponse.Content.ReadAsStreamAsync(cancellationToken), cancellationToken: cancellationToken);
        if (!statusJson.RootElement.TryGetProperty("status", out var status)
            || !status.TryGetProperty("code", out var codeElement)
            || !codeElement.TryGetInt32(out var code))
            return;

        if (code is 10 or 20)
            return;

        if (code is 30 or 50)
        {
            await MarkFailedAsync(dbContext, cardId, taskId,
                "Không dựng được mô hình. Hãy chụp thêm ảnh rõ nét, đủ sáng và chồng lấp góc nhìn.", cancellationToken);
            await RemoveNodeOdmTaskAsync(client, taskId, cancellationToken);
            return;
        }

        if (code != 40)
            return;

        var card = await dbContext.ArInfoCards.FirstOrDefaultAsync(x => x.Id == cardId, cancellationToken);
        if (card is null || !string.Equals(card.NodeOdmTaskId, taskId, StringComparison.Ordinal))
            return;

        var temporaryArchive = Path.Combine(Path.GetTempPath(), $"ar-model-{Guid.NewGuid():N}.zip");
        var temporaryModel = Path.Combine(Path.GetTempPath(), $"ar-model-{Guid.NewGuid():N}.glb");
        try
        {
            await DownloadNodeOdmArchiveAsync(client, taskId, temporaryArchive, cancellationToken);
            await using var archiveFile = File.OpenRead(temporaryArchive);
            using var archive = new ZipArchive(archiveFile, ZipArchiveMode.Read, leaveOpen: false);
            var modelEntry = archive.Entries.FirstOrDefault(entry =>
                entry.FullName.Replace('\\', '/').Equals(ExpectedModelPath, StringComparison.OrdinalIgnoreCase));
            if (modelEntry is null || modelEntry.Length <= 0 || modelEntry.Length > _options.MaxModelBytes)
                throw new InvalidDataException("NodeODM archive did not contain a usable GLB model.");

            await ExtractModelAsync(modelEntry, temporaryModel, cancellationToken);
            var objectKey = $"ar-models/{cardId:N}/{Guid.NewGuid():N}.glb";
            StorageUploadResult uploadResult;
            await using (var modelStream = File.OpenRead(temporaryModel))
            {
                uploadResult = await _objectStorageService.UploadPhotoAsync(
                    modelStream,
                    "model/gltf-binary",
                    objectKey,
                    cancellationToken);
            }

            var oldObjectKey = card.ModelObjectKey;
            card.CompleteModelProcessing(uploadResult.ObjectKey, DateTimeOffset.UtcNow);
            await dbContext.SaveChangesAsync(cancellationToken);

            var oldObjectIsShared = !string.IsNullOrWhiteSpace(oldObjectKey) &&
                await dbContext.ArInfoCards.AnyAsync(
                    x => x.Id != cardId && x.ModelObjectKey == oldObjectKey,
                    cancellationToken);
            if (!oldObjectIsShared && !string.IsNullOrWhiteSpace(oldObjectKey))
            {
                try
                {
                    await _objectStorageService.DeleteObjectAsync(oldObjectKey, cancellationToken);
                }
                catch (Exception exception)
                {
                    _logger.LogWarning(exception, "Could not remove the previous AR model object for card {CardId}.", cardId);
                }
            }

            await RemoveNodeOdmTaskAsync(client, taskId, cancellationToken);
            _logger.LogInformation("3D model for AR card {CardId} is ready.", cardId);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            _logger.LogError(exception, "Could not store the generated GLB for AR card {CardId}.", cardId);
            if (exception is InvalidDataException)
            {
                await MarkFailedAsync(dbContext, cardId, taskId,
                    "Worker không tạo được tệp GLB hợp lệ từ bộ ảnh này.", cancellationToken);
                await RemoveNodeOdmTaskAsync(client, taskId, cancellationToken);
            }
        }
        finally
        {
            try { File.Delete(temporaryArchive); }
            catch (IOException) { }
            try { File.Delete(temporaryModel); }
            catch (IOException) { }
        }
    }

    private async Task ExtractModelAsync(
        ZipArchiveEntry modelEntry,
        string destinationPath,
        CancellationToken cancellationToken)
    {
        await using var input = modelEntry.Open();
        await using var output = new FileStream(destinationPath, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        var buffer = new byte[64 * 1024];
        long copied = 0;
        while (true)
        {
            var read = await input.ReadAsync(buffer, cancellationToken);
            if (read == 0) break;
            copied += read;
            if (copied > _options.MaxModelBytes)
                throw new InvalidDataException("Generated GLB exceeded the configured model size.");
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
        }

        if (copied == 0)
            throw new InvalidDataException("Generated GLB was empty.");
    }

    private async Task DownloadNodeOdmArchiveAsync(
        HttpClient client,
        string taskId,
        string destinationPath,
        CancellationToken cancellationToken)
    {
        using var response = await client.GetAsync(
            BuildNodeOdmUri($"task/{taskId}/download/all.zip"),
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength is long length &&
            length > _options.MaxModelBytes + 10L * 1024 * 1024)
            throw new InvalidDataException("NodeODM model archive exceeded the configured download size.");

        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        await using var output = new FileStream(destinationPath, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        var buffer = new byte[64 * 1024];
        long copied = 0;
        while (true)
        {
            var read = await input.ReadAsync(buffer, cancellationToken);
            if (read == 0) break;
            copied += read;
            if (copied > _options.MaxModelBytes + 10L * 1024 * 1024)
                throw new InvalidDataException("NodeODM model archive exceeded the configured download size.");
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
        }
    }

    private async Task MarkFailedAsync(
        ArExperienceDbContext dbContext,
        Guid cardId,
        string taskId,
        string error,
        CancellationToken cancellationToken)
    {
        var card = await dbContext.ArInfoCards.FirstOrDefaultAsync(x => x.Id == cardId, cancellationToken);
        if (card is null || !string.Equals(card.NodeOdmTaskId, taskId, StringComparison.Ordinal))
            return;
        card.FailModelProcessing(error, DateTimeOffset.UtcNow);
        await dbContext.SaveChangesAsync(cancellationToken);
    }

    private async Task RemoveNodeOdmTaskAsync(
        HttpClient client,
        string taskId,
        CancellationToken cancellationToken)
    {
        try
        {
            using var content = new StringContent(
                JsonSerializer.Serialize(new { uuid = taskId }),
                System.Text.Encoding.UTF8,
                "application/json");
            using var response = await client.PostAsync(BuildNodeOdmUri("task/remove"), content, cancellationToken);
            if (!response.IsSuccessStatusCode)
                _logger.LogDebug("NodeODM task cleanup returned HTTP {StatusCode}.", (int)response.StatusCode);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            _logger.LogDebug(exception, "Could not remove completed NodeODM task {TaskId}.", taskId);
        }
    }

    private Uri BuildNodeOdmUri(string path)
    {
        var uri = new Uri(new Uri(_options.NodeOdmUrl.TrimEnd('/') + "/"), path);
        if (string.IsNullOrWhiteSpace(_options.NodeOdmToken))
            return uri;
        var builder = new UriBuilder(uri) { Query = $"token={Uri.EscapeDataString(_options.NodeOdmToken)}" };
        return builder.Uri;
    }
}
