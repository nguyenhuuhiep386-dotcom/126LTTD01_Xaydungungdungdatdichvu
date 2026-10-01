using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Services.Catalog;

namespace Servio.Api.Pages.Admin.Catalog;

/// <summary>
/// AW-11 — service categories (read-only for now).
/// Reference page for the other AW pages: PageModel calls a Service, the .cshtml only renders.
/// </summary>
public sealed class CategoriesModel(CategoryService categories) : PageModel
{
    public IReadOnlyList<CategoryDto> Groups { get; private set; } = [];

    public async Task OnGetAsync(CancellationToken ct) => Groups = await categories.GetTreeAsync(null, ct);
}
