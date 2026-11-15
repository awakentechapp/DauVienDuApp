namespace SightseeingTravel.SharedKernel.Contracts;

public interface IUserDisplayNameResolver
{
    Task<IReadOnlyDictionary<Guid, string>> ResolveAsync(
        IEnumerable<Guid> userIds,
        CancellationToken cancellationToken = default);
}
