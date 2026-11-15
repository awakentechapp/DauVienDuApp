using Microsoft.EntityFrameworkCore;
using SightseeingTravel.SharedKernel.Contracts;

namespace SightseeingTravel.Modules.Identity.Infrastructure;

public sealed class IdentityUserDisplayNameResolver : IUserDisplayNameResolver
{
    private readonly IdentityDbContext _dbContext;

    public IdentityUserDisplayNameResolver(IdentityDbContext dbContext)
    {
        _dbContext = dbContext;
    }

    public async Task<IReadOnlyDictionary<Guid, string>> ResolveAsync(
        IEnumerable<Guid> userIds,
        CancellationToken cancellationToken = default)
    {
        var ids = userIds.Where(id => id != Guid.Empty).Distinct().ToArray();
        if (ids.Length == 0)
        {
            return new Dictionary<Guid, string>();
        }

        var users = await _dbContext.Users
            .AsNoTracking()
            .Where(user => ids.Contains(user.Id))
            .Select(user => new { user.Id, user.DisplayName, user.Email })
            .ToListAsync(cancellationToken);

        return users.ToDictionary(
            user => user.Id,
            user => string.IsNullOrWhiteSpace(user.DisplayName)
                ? user.Email
                : user.DisplayName.Trim());
    }
}
