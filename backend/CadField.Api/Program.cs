using System.Text;
using CadField.Api.Data;
using CadField.Api.Endpoints;
using CadField.Api.Storage;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;

var builder = WebApplication.CreateBuilder(args);
var cfg = builder.Configuration;

builder.Services.AddDbContext<AppDbContext>(o => o.UseNpgsql(cfg.GetConnectionString("Postgres")));
builder.Services.AddStackExchangeRedisCache(o => o.Configuration = cfg.GetConnectionString("Redis"));
builder.Services.AddSingleton<IBlobStore>(new FileBlobStore(cfg["Storage:Root"] ?? "/data/objects"));
builder.Services.AddOpenApi();
builder.Services.AddProblemDetails();

// OAuth2 / OpenID Connect: en producción, Authority = proveedor corporativo (Microsoft Entra ID, Keycloak...).
// En desarrollo se admite una clave simétrica local para emitir JWT de prueba.
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(o =>
{
    var authority = cfg["Auth:Authority"];
    if (!string.IsNullOrEmpty(authority))
    {
        o.Authority = authority;
        o.Audience = cfg["Auth:Audience"];
    }
    else
    {
        var key = cfg["Auth:DevSigningKey"] ?? throw new InvalidOperationException("Configure Auth:Authority o Auth:DevSigningKey");
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = false, ValidateAudience = false, ValidateLifetime = true,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(key)),
        };
    }
});
builder.Services.AddAuthorization();
builder.Services.AddHealthChecks().AddDbContextCheck<AppDbContext>();

var app = builder.Build();

app.UseExceptionHandler();
app.UseAuthentication();
app.UseAuthorization();
if (app.Environment.IsDevelopment()) app.MapOpenApi();
app.MapHealthChecks("/health");
app.MapSync();

if (app.Environment.IsDevelopment())
{
    using var scope = app.Services.CreateScope();
    scope.ServiceProvider.GetRequiredService<AppDbContext>().Database.EnsureCreated();
}

app.Run();
