namespace SightseeingTravel.SharedKernel.Common;

public enum ErrorType
{
    Validation = 400,
    Unauthorized = 401,
    Forbidden = 403,
    NotFound = 404,
    Conflict = 409,
    UnprocessableEntity = 422,
    Failure = 500
}

public static class ErrorCodes
{
    public const string AuthInvalidCredentials = "AUTH_INVALID_CREDENTIALS";
    public const string AuthAccountLocked = "AUTH_ACCOUNT_LOCKED";
    public const string AuthForbiddenRole = "AUTH_FORBIDDEN_ROLE";
    public const string AuthEmailAlreadyExists = "AUTH_EMAIL_ALREADY_EXISTS";
    public const string AuthInvalidRefreshToken = "AUTH_INVALID_REFRESH_TOKEN";

    public const string PoiNotFound = "POI_NOT_FOUND";
    public const string PoiCodeAlreadyExists = "POI_CODE_ALREADY_EXISTS";
    public const string PoiOutOfGeofenceRange = "POI_OUT_OF_GEOFENCE_RANGE";
    public const string PoiRejectReasonRequired = "POI_REJECT_REASON_REQUIRED";
    public const string PoiInvalidStateTransition = "POI_INVALID_STATE_TRANSITION";

    public const string ArCardNotFound = "AR_CARD_NOT_FOUND";

    public const string PhotoNotFound = "PHOTO_NOT_FOUND";
    public const string PhotoInvalidFormatOrSize = "PHOTO_INVALID_FORMAT_OR_SIZE";
    public const string PhotoAlreadyReported = "PHOTO_ALREADY_REPORTED";
}

public sealed record Error(string Code, string Message, ErrorType Type = ErrorType.Validation)
{
    public static readonly Error None = new(string.Empty, string.Empty, ErrorType.Validation);
}

public class Result
{
    public bool IsSuccess { get; }
    public bool IsFailure => !IsSuccess;
    public Error Error { get; }

    protected Result(bool isSuccess, Error error)
    {
        IsSuccess = isSuccess;
        Error = error;
    }

    public static Result Success() => new(true, Error.None);
    public static Result Failure(Error error) => new(false, error);
    public static Result<T> Success<T>(T value) => new(value, true, Error.None);
    public static Result<T> Failure<T>(Error error) => new(default, false, error);
}

public sealed class Result<T> : Result
{
    private readonly T? _value;

    public T Value => IsSuccess
        ? _value!
        : throw new InvalidOperationException($"Cannot access Value of failed Result ({Error.Code}: {Error.Message}).");

    public Result(T? value, bool isSuccess, Error error)
        : base(isSuccess, error)
    {
        _value = value;
    }
}
