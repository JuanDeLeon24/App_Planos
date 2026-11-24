using System.Security.Cryptography;

namespace CadField.Api.Storage;

public interface IBlobStore
{
    /// <summary>Guarda el flujo si su SHA-256 coincide con <paramref name="expectedHash"/>. Write-once.</summary>
    Task<long> PutAsync(string expectedHash, Stream content, CancellationToken ct);
    Stream? Open(string hash);
    bool Exists(string hash);
}

/// <summary>
/// Almacén de blobs en disco/volumen (en producción: Azure Blob / S3 con inmutabilidad WORM
/// y versionado habilitado). Escritura atómica: temp + verificación de hash + move.
/// </summary>
public sealed class FileBlobStore(string root) : IBlobStore
{
    private string PathOf(string hash)
    {
        if (hash.Length != 64 || !hash.All(Uri.IsHexDigit)) throw new ArgumentException("Hash inválido");
        return Path.Combine(root, hash[..2], hash[2..]);
    }

    public bool Exists(string hash) => File.Exists(PathOf(hash));

    public Stream? Open(string hash) => Exists(hash) ? File.OpenRead(PathOf(hash)) : null;

    public async Task<long> PutAsync(string expectedHash, Stream content, CancellationToken ct)
    {
        var dest = PathOf(expectedHash);
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        var tmp = dest + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            long size;
            string actual;
            await using (var fs = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 16, true))
            using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256))
            {
                var buf = new byte[1 << 16];
                int n;
                while ((n = await content.ReadAsync(buf, ct)) > 0)
                {
                    sha.AppendData(buf, 0, n);
                    await fs.WriteAsync(buf.AsMemory(0, n), ct);
                }
                await fs.FlushAsync(ct);
                fs.Flush(flushToDisk: true);
                size = fs.Length;
                actual = Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
            }
            if (actual != expectedHash) throw new InvalidDataException($"Hash no coincide: {actual}");
            if (!File.Exists(dest)) File.Move(tmp, dest); // nunca sobrescribe
            return size;
        }
        finally
        {
            if (File.Exists(tmp)) File.Delete(tmp);
        }
    }
}
