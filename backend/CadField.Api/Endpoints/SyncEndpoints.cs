using System.Security.Claims;
using System.Text.Json;
using CadField.Api.Data;
using CadField.Api.Storage;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Caching.Distributed;

namespace CadField.Api.Endpoints;

public record NegotiateRequest(List<string> Hashes);
public record NegotiateResponse(List<string> Missing);
public record RefUpdate(string Branch, string? ExpectedOld, string New);
public record RefState(string Branch, string CommitId);
public record ProjectUpsert(string Name);
public record DocumentUpsert(Guid ProjectId, string DisplayName, string OriginalHash, string? Discipline, string? Front, string? Tunnel);

/// <summary>
/// Protocolo de sincronización diferencial (ver docs/09-estrategia-sincronizacion.md):
///   1. negotiate → el cliente sólo sube lo que falta
///   2. PUT objects/{hash} → verificado por hash, write-once
///   3. POST refs → compare-and-swap; 409 si la rama avanzó (el cliente fusiona y reintenta)
/// </summary>
public static class SyncEndpoints
{
    private static string UserId(ClaimsPrincipal u) =>
        u.FindFirstValue("preferred_username") ?? u.FindFirstValue(ClaimTypes.NameIdentifier) ?? "desconocido";

    private static async Task<bool> CanAccess(AppDbContext db, ClaimsPrincipal u, Guid docId, bool write)
    {
        var uid = UserId(u);
        return await db.Documents.Where(d => d.Id == docId)
            .Join(db.Memberships, d => d.ProjectId, m => m.ProjectId, (d, m) => m)
            .AnyAsync(m => m.UserId == uid && (!write || m.Role != "Viewer"));
    }

    public static void MapSync(this IEndpointRouteBuilder app)
    {
        var g = app.MapGroup("/api/v1").RequireAuthorization();

        // Registro idempotente: el creador del proyecto queda como Admin.
        g.MapPut("/projects/{id:guid}", async (Guid id, ProjectUpsert req, AppDbContext db, ClaimsPrincipal user) =>
        {
            var uid = UserId(user);
            var p = await db.Projects.FindAsync(id);
            if (p is null)
            {
                db.Projects.Add(new Project { Id = id, Name = req.Name, OrganizationId = user.FindFirstValue("tid") ?? "default" });
                db.Memberships.Add(new Membership { ProjectId = id, UserId = uid, Role = "Admin" });
            }
            else if (!await db.Memberships.AnyAsync(m => m.ProjectId == id && m.UserId == uid && m.Role == "Admin"))
                return Results.Forbid();
            else p.Name = req.Name;
            await db.SaveChangesAsync();
            return Results.NoContent();
        });

        g.MapPut("/documents/{id:guid}", async (Guid id, DocumentUpsert req, AppDbContext db, ClaimsPrincipal user) =>
        {
            var uid = UserId(user);
            if (!await db.Memberships.AnyAsync(m => m.ProjectId == req.ProjectId && m.UserId == uid && m.Role != "Viewer"))
                return Results.Forbid();
            var d = await db.Documents.FindAsync(id);
            if (d is null)
                db.Documents.Add(new Document { Id = id, ProjectId = req.ProjectId, DisplayName = req.DisplayName, OriginalHash = req.OriginalHash,
                    Discipline = req.Discipline, Front = req.Front, Tunnel = req.Tunnel });
            else if (d.ProjectId != req.ProjectId || d.OriginalHash != req.OriginalHash)
                return Results.Conflict("El original de un documento es inmutable");
            else { d.DisplayName = req.DisplayName; d.Discipline = req.Discipline; d.Front = req.Front; d.Tunnel = req.Tunnel; }
            await db.SaveChangesAsync();
            return Results.NoContent();
        });

        g.MapPost("/documents/{docId:guid}/sync/negotiate", async (Guid docId, NegotiateRequest req, AppDbContext db, ClaimsPrincipal user) =>
        {
            if (!await CanAccess(db, user, docId, write: true)) return Results.Forbid();
            if (req.Hashes.Count > 10_000) return Results.BadRequest("Demasiados hashes por solicitud");
            var existing = await db.Objects.Where(o => req.Hashes.Contains(o.Hash)).Select(o => o.Hash).ToListAsync();
            // vincular al documento los objetos que ya existen (deduplicación entre documentos)
            var linked = await db.ObjectLinks.Where(l => l.DocumentId == docId && existing.Contains(l.Hash)).Select(l => l.Hash).ToListAsync();
            db.ObjectLinks.AddRange(existing.Except(linked).Select(h => new ObjectLink { DocumentId = docId, Hash = h }));
            await db.SaveChangesAsync();
            return Results.Ok(new NegotiateResponse(req.Hashes.Except(existing).Distinct().ToList()));
        });

        g.MapPut("/objects/{hash}", async (string hash, HttpRequest http, AppDbContext db, IBlobStore blobs, ClaimsPrincipal user, CancellationToken ct) =>
        {
            if (await db.Objects.AnyAsync(o => o.Hash == hash, ct)) return Results.NoContent();
            long size;
            try { size = await blobs.PutAsync(hash, http.Body, ct); }
            catch (InvalidDataException e) { return Results.BadRequest(e.Message); }
            db.Objects.Add(new StoredObject { Hash = hash, Size = size });
            db.Audit.Add(new AuditEntry { UserId = UserId(user), Action = "OBJECT_PUT", Detail = $"{hash} ({size} B)", Ip = http.HttpContext.Connection.RemoteIpAddress?.ToString() });
            try { await db.SaveChangesAsync(ct); } catch (DbUpdateException) { /* subida concurrente del mismo objeto: idempotente */ }
            return Results.Created($"/api/v1/objects/{hash}", null);
        }).DisableAntiforgery().WithMetadata(new Microsoft.AspNetCore.Mvc.RequestSizeLimitAttribute(2L * 1024 * 1024 * 1024));

        g.MapGet("/objects/{hash}", async (string hash, AppDbContext db, IBlobStore blobs, ClaimsPrincipal user) =>
        {
            var uid = UserId(user);
            var allowed = await db.ObjectLinks.Where(l => l.Hash == hash)
                .Join(db.Documents, l => l.DocumentId, d => d.Id, (l, d) => d.ProjectId)
                .Join(db.Memberships, p => p, m => m.ProjectId, (p, m) => m.UserId)
                .AnyAsync(u => u == uid);
            if (!allowed) return Results.NotFound();
            var s = blobs.Open(hash);
            return s is null ? Results.NotFound() : Results.Stream(s, "application/octet-stream", enableRangeProcessing: true);
        });

        g.MapGet("/documents/{docId:guid}/refs", async (Guid docId, AppDbContext db, IDistributedCache cache, ClaimsPrincipal user) =>
        {
            if (!await CanAccess(db, user, docId, write: false)) return Results.Forbid();
            var key = $"refs:{docId}";
            var cached = await cache.GetStringAsync(key);
            if (cached is not null) return Results.Content(cached, "application/json");
            var refs = await db.Refs.Where(r => r.DocumentId == docId).Select(r => new RefState(r.Branch, r.CommitId)).ToListAsync();
            var json = JsonSerializer.Serialize(refs, JsonSerializerOptions.Web);
            await cache.SetStringAsync(key, json, new DistributedCacheEntryOptions { AbsoluteExpirationRelativeToNow = TimeSpan.FromMinutes(5) });
            return Results.Content(json, "application/json");
        });

        g.MapPost("/documents/{docId:guid}/refs", async (Guid docId, RefUpdate upd, AppDbContext db, IDistributedCache cache, ClaimsPrincipal user, HttpContext http) =>
        {
            if (!await CanAccess(db, user, docId, write: true)) return Results.Forbid();
            if (!await db.Objects.AnyAsync(o => o.Hash == upd.New)) return Results.BadRequest("El commit no fue subido");
            var current = await db.Refs.SingleOrDefaultAsync(r => r.DocumentId == docId && r.Branch == upd.Branch);
            if (current?.CommitId != upd.ExpectedOld && !(current is null && upd.ExpectedOld is null))
                return Results.Conflict(new RefState(upd.Branch, current?.CommitId ?? ""));
            if (current is null)
                db.Refs.Add(new BranchRef { DocumentId = docId, Branch = upd.Branch, CommitId = upd.New, UpdatedBy = UserId(user) });
            else
            {
                current.CommitId = upd.New;
                current.UpdatedBy = UserId(user);
                current.UpdatedAt = DateTimeOffset.UtcNow;
            }
            db.Audit.Add(new AuditEntry { UserId = UserId(user), Action = "REF_UPDATE", DocumentId = docId,
                Detail = $"{upd.Branch}: {upd.ExpectedOld ?? "∅"} → {upd.New}", Ip = http.Connection.RemoteIpAddress?.ToString() });
            try { await db.SaveChangesAsync(); }
            catch (DbUpdateConcurrencyException)
            {
                var now = await db.Refs.AsNoTracking().SingleAsync(r => r.DocumentId == docId && r.Branch == upd.Branch);
                return Results.Conflict(new RefState(upd.Branch, now.CommitId));
            }
            await cache.RemoveAsync($"refs:{docId}");
            return Results.Ok(new RefState(upd.Branch, upd.New));
        });

        g.MapGet("/documents/{docId:guid}/audit", async (Guid docId, AppDbContext db, ClaimsPrincipal user) =>
            await CanAccess(db, user, docId, write: false)
                ? Results.Ok(await db.Audit.Where(a => a.DocumentId == docId).OrderByDescending(a => a.Timestamp).Take(500).ToListAsync())
                : Results.Forbid());
    }
}
