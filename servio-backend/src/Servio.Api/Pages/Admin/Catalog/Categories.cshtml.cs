using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Common;
using Servio.Api.Services.Admin;

namespace Servio.Api.Pages.Admin.Catalog;

/// <summary>
/// AW-11 — service categories, including inactive ones.
/// Reference page for the other AW pages: PageModel calls a Service, the .cshtml only renders.
/// </summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class CategoriesModel(AdminCategoryService categories) : PageModel
{
    public IReadOnlyList<AdminCategoryRow> Groups { get; private set; } = [];
    public ILookup<Guid?, AdminCategoryRow> Children { get; private set; } = Enumerable.Empty<AdminCategoryRow>().ToLookup(c => c.ParentId);

    [TempData]
    public string? Flash { get; set; }

    public async Task OnGetAsync(CancellationToken ct)
    {
        var all = await categories.ListAsync(ct);
        Groups = all.Where(c => c.ParentId is null).ToList();
        Children = all.Where(c => c.ParentId is not null).ToLookup(c => c.ParentId);
    }
}
