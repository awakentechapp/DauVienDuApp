using Microsoft.EntityFrameworkCore;

namespace SightseeingTravel.Persistence;

public static class PostgreSqlModelBuilderExtensions
{
    public static void ApplySnakeCaseColumnNames(this ModelBuilder modelBuilder)
    {
        foreach (var entityType in modelBuilder.Model.GetEntityTypes())
        {
            foreach (var property in entityType.GetProperties())
            {
                property.SetColumnName(ToSnakeCase(property.Name));
            }
        }
    }

    private static string ToSnakeCase(string value)
    {
        var result = new System.Text.StringBuilder(value.Length + 8);

        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (char.IsUpper(current))
            {
                var previousIsLowerOrDigit = index > 0 &&
                    (char.IsLower(value[index - 1]) || char.IsDigit(value[index - 1]));
                var nextIsLower = index + 1 < value.Length && char.IsLower(value[index + 1]);

                if (index > 0 && (previousIsLowerOrDigit || nextIsLower))
                    result.Append('_');

                result.Append(char.ToLowerInvariant(current));
            }
            else
            {
                result.Append(current);
            }
        }

        return result.ToString();
    }
}
