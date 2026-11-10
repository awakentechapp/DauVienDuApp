namespace SightseeingTravel.SharedKernel.Common;

public static class ImageSignatureValidator
{
    public const long MaxAllowedSizeBytes = 5 * 1024 * 1024; // 5 MB

    public static Result<string> ValidateAndGetExtension(
        Stream fileStream,
        long lengthBytes,
        string? contentType)
    {
        if (lengthBytes <= 0 || lengthBytes > MaxAllowedSizeBytes)
        {
            return Result.Failure<string>(new Error(
                ErrorCodes.PhotoInvalidFormatOrSize,
                $"Image file size must be between 1 byte and {MaxAllowedSizeBytes / (1024 * 1024)} MB.",
                ErrorType.Validation));
        }

        if (!fileStream.CanSeek)
        {
            return Result.Failure<string>(new Error(
                ErrorCodes.PhotoInvalidFormatOrSize,
                "Image stream must support seeking so its contents can be validated without consuming the upload.",
                ErrorType.Validation));
        }

        Span<byte> header = stackalloc byte[12];
        fileStream.Position = 0;

        var bytesRead = 0;
        try
        {
            while (bytesRead < header.Length)
            {
                var read = fileStream.Read(header[bytesRead..]);
                if (read == 0)
                    break;
                bytesRead += read;
            }
        }
        finally
        {
            fileStream.Position = 0;
        }

        if (bytesRead < 4)
        {
            return Result.Failure<string>(new Error(
                ErrorCodes.PhotoInvalidFormatOrSize,
                "Invalid or corrupted image file header.",
                ErrorType.Validation));
        }

        // JPEG: FF D8 FF
        if (header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF)
            return Result.Success(".jpg");

        // PNG: 89 50 4E 47
        if (header[0] == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47)
            return Result.Success(".png");

        // WEBP: RIFF....WEBP
        if (bytesRead >= 12 &&
            header[0] == (byte)'R' && header[1] == (byte)'I' && header[2] == (byte)'F' && header[3] == (byte)'F' &&
            header[8] == (byte)'W' && header[9] == (byte)'E' && header[10] == (byte)'B' && header[11] == (byte)'P')
        {
            return Result.Success(".webp");
        }

        return Result.Failure<string>(new Error(
            ErrorCodes.PhotoInvalidFormatOrSize,
            "Only JPEG, PNG, and WEBP images are allowed.",
            ErrorType.Validation));
    }
}
