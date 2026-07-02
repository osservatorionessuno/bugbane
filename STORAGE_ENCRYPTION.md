# Storage & Encryption

How bugbane stores an acquisition encrypted at rest, how it reads it back for
analysis, and how it exports it — plus a map of the code that implements this.

The acquisition is written as **one file, `acquisition.age`**, which is a
**standard ZIP wrapped in a standard age file**:

```
acquisition.age  =  age( ZIP( artifact1, artifact2, … ) )
```

- **age** gives authenticated, streaming encryption (ChaCha20-Poly1305) with a
  recipient model.
- **ZIP** (Deflate) is the container that holds the individual artifacts.

Plaintext never touches disk during acquisition: each module streams its bytes
straight through `Deflate → ChaCha20-Poly1305 → file`. The decrypted result is a
plain ZIP, so an analyst can open an export with stock tools (`age -d … | unzip`).

> Goal: protect a seized device's acquisition against **offline** forensic
> extraction. See [THREAT_MODELING.md](THREAT_MODELING.md) for the threat model.
> Note the current limitation in [Guarantees & limitations](#guarantees--limitations):
> the device key is not yet gated by per-use authentication.

---

## On-disk format

```
acquisition.age
┌────────────────────────────────────────────────────────────────────┐
│ age v1 header (ASCII text)                                           │
│   age-encryption.org/v1                                              │
│   -> bugbane-se   <base64: fileKey wrapped by the device KEK>        │  ← recipient stanza
│   --- <base64: HMAC-SHA256 over the header, keyed from the fileKey>  │  ← header MAC
├────────────────────────────────────────────────────────────────────┤
│ age payload                                                          │
│   nonce (16 bytes)                                                   │
│   ChaCha20-Poly1305 STREAM, 64 KiB chunks, of:                       │
│       ZIP (Deflate, streaming/data-descriptors) of:                  │
│           getprop.txt, packages.json, logs/…, apks/…, …              │
└────────────────────────────────────────────────────────────────────┘
```

- **Header** is the standard age v1 text header. The single recipient stanza
  type is `bugbane-se` (StrongBox) or `bugbane-tee` (TEE) — see the key
  hierarchy below.
- **Payload** is age's STREAM construction: a random 16-byte nonce, then 64 KiB
  plaintext chunks each sealed with ChaCha20-Poly1305. The per-chunk nonce is an
  11-byte big-endian counter plus a final-chunk flag, so truncation is detected.
- The **plaintext fed into the STREAM is a ZIP** (Deflate, `BEST_SPEED`) written
  with *data descriptors* so entries can be streamed without knowing their size
  up front.

A separate plaintext **`acquisition.json`** sidecar (uuid, timestamps, device
props, `streaming_mode: true`) sits next to `acquisition.age` for the UI to list
acquisitions; scan results are written to a plaintext **`analysis/*.json`**.
These are metadata, not the captured artifacts — see limitations.

---

## Key hierarchy (envelope: KEK → file key → payload)

```
KEK        AES-256-GCM, non-exportable, in Android Keystore (StrongBox or TEE)
  │        alias "bugbane.acquisition.kek"; the raw key never leaves hardware
  └─ wraps ─► fileKey   16 random bytes, generated per archive
                │        stored (wrapped) as the "-> bugbane-se/tee" stanza body
                └─ HKDF ─► streamKey = HKDF(salt=nonce, ikm=fileKey, info="payload")
                             encrypts the ZIP payload (ChaCha20-Poly1305 STREAM)
```

- The **KEK** is a hardware key. We only hold an opaque handle; wrap/unwrap run
  *inside* the TEE/StrongBox. Encrypting at rest protects against extracting the
  archive offline.
- The **file key** is the per-archive data key. It is wrapped by the KEK on
  write and unwrapped (by the hardware) on read; it must exist briefly in app
  memory because age does ChaCha in software.
- **Export** re-wraps the *same file key* to a different recipient (a passphrase
  or an analyst's X25519 key) without re-encrypting the payload — see flows.

The stanza type records the hardware backing so a reader can warn the user:
`bugbane-se` = StrongBox (strong credential rate-limiting), `bugbane-tee` = TEE
only (consider an extra passphrase factor).

---

## Code map

Everything crypto lives in the pure-JVM **`:crypto`** module
(`crypto/src/main/kotlin/org/osservatorionessuno/qf/crypto/`); the one
Android-specific piece (the Keystore) lives in `:app`.

| File | Responsibility |
|---|---|
| `age/AgePrimitives.kt` | All crypto from **one backend (BouncyCastle)**: HKDF-SHA256, HMAC-SHA256, ChaCha20-Poly1305, scrypt, X25519, secure random. |
| `age/AgeFormat.kt` | age v1 header **parse** (bounded), **serialize**, and **MAC** (compute + constant-time verify). Defines `AgeStanza`, `AgeRecipient`, `AgeIdentity`, `ParsedHeader`, `AgeFormatException`. |
| `age/Age.kt` | `Age.encryptingStream(recipients, out)` → a push `OutputStream` that writes header + nonce then STREAM-encrypts. `AgeEncryptingOutputStream` (one-chunk lookahead), `streamNonce`. |
| `age/AgePayload.kt` | `AgePayload.open(source, identities)` → parses header, unwraps the file key, **verifies the MAC**, then exposes `read(off,len)` (random access) and `stream()` (sequential). No whole-file buffering. |
| `age/AgeRecipients.kt` | `X25519Recipient`/`X25519Identity`, `ScryptRecipient`/`ScryptIdentity` (for export). |
| `KeyVault.kt` | `KeyVault` interface (`stanzaType`, `wrap`, `unwrap`) + `InMemoryKeyVault` (tests only). |
| `KeyVaultStanza.kt` | `KeyVaultRecipient`/`KeyVaultIdentity` — the custom `bugbane-*` stanza that wraps the file key under a `KeyVault`. |
| `ArchiveWriter.kt` | Push writer: `ZipOutputStream(BEST_SPEED)` over `Age.encryptingStream(...)`. One stream stack — no pipe/thread. |
| `EncryptedArchive.kt` | `write(out, vault, entries)` and `forEachEntry(file/data, vault) { name, mtime, stream -> }` (decrypt + unzip in one bounded-memory pass). `Entry`. |
| `SeekableArchive.kt` | Random-access single-file read: `RandomAccessData` (+ `ByteArrayRandomAccess`, `FileRandomAccess`), `SeekableArchive` (Commons Compress `ZipFile` over `AgePayloadChannel`, a `SeekableByteChannel` backed by `AgePayload`). |
| `AgeExporter.kt` | `export(atRest, vault, recipient, out)` — verbatim re-wrap to a portable recipient. |
| `app/.../qf/crypto/AndroidKeystoreKeyVault.kt` | Production `KeyVault`: AES-256-GCM KEK in Keystore (StrongBox-preferred), `getOrCreate()`, `bugbane-se`/`bugbane-tee`. |

App wiring:

| File | Responsibility |
|---|---|
| `app/.../qf/storage/ArtifactSink.kt` | Storage contracts: `ArtifactSink` (`openArtifact`/`useArtifact`/`artifactExists`) and `ArtifactReader` (`forEachArtifact`). |
| `app/.../qf/storage/EncryptedAcquisition.kt` | `EncryptedAcquisitionWriter`/`EncryptedAcquisitionReader` — the storage contracts over `ArchiveWriter`/`EncryptedArchive`, plus the plaintext `acquisition.json` index (metadata only). |
| `app/.../qf/Module.kt` | Module contract: `run(context, manager, writer: ArtifactSink, progress)`. |
| `app/.../qf/modules/*.kt` | Each module streams artifacts into `writer.useArtifact("name") { … }`. |
| `app/.../qf/AcquisitionRunner.kt` | Opens one `EncryptedAcquisitionWriter` over `<uuid>/acquisition.age` keyed by `AndroidKeystoreKeyVault.getOrCreate()`; writes the `acquisition.json` sidecar. |
| `app/.../qf/AcquisitionScanner.kt` | Analyzes via `EncryptedAcquisitionReader.forEachArtifact` + libMVT `streamFileAnalysis`, straight from the encrypted archive. |
| `app/.../bugbane/screens/AcquisitionDetailScreen.kt` | `createEncryptedArchive(...)` builds a shareable export (`AgeExporter` for encrypted acquisitions; passphrase recipient). |

---

## Data flows

### 1. Acquire (write) — no plaintext to disk

`AcquisitionRunner.run` →

```
AndroidKeystoreKeyVault.getOrCreate()                    // KEK handle (StrongBox/TEE)
EncryptedAcquisitionWriter(acquisitionDir, vault)
  └─ ArchiveWriter(FileOutputStream(acquisition.age), vault):
       ZipOutputStream(BEST_SPEED) → Age.encryptingStream([KeyVaultRecipient(vault)], out)
for each module:
   module.run(ctx, adb, writer) → writer.useArtifact("getprop.txt") { … stream bytes … }
```

`Age.encryptingStream` generates the random file key, calls
`KeyVaultRecipient.wrap` (→ KEK wraps it → stanza), writes the header + nonce,
and returns the STREAM `OutputStream`. Module bytes flow
`Deflate → ChaCha20-Poly1305 → acquisition.age`. See `ArchiveWriter.kt`,
`age/Age.kt`, `KeyVaultStanza.kt`.

### 2. Analyze (read) — bounded memory

`AcquisitionScanner.scan` →

```
EncryptedArchive.forEachEntry(acquisition.age, AndroidKeystoreKeyVault.getOrCreate()) { name, stream ->
    if (name in libMVT modules) runner.streamFileAnalysis(name, stream)
}
```

`forEachEntry` builds `AgePayload.open(...)` (unwrap file key via `KeyVaultIdentity`,
**verify header MAC**), then `ZipInputStream(payload.stream())` and iterates
entries — one 64 KiB chunk decrypted at a time, never the whole archive. See
`EncryptedArchive.kt`, `age/AgePayload.kt`.

### 3. Seek (one artifact, random access)

```
SeekableArchive(FileRandomAccess(acquisition.age), vault).open("packages.json")
```

`AgePayload` decrypts only the age chunks spanning the requested entry; Commons
Compress's `ZipFile` reads the ZIP central directory over a decrypting
`SeekableByteChannel` and inflates just that entry. See `SeekableArchive.kt`.

### 4. Export / share — verbatim re-wrap

```
AgeExporter.export(acquisition.age, vault, ScryptRecipient(passphrase, logN=15), out)
```

Parses the header, unwraps the file key (verifying the at-rest MAC), re-wraps the
**same** file key to the passphrase (or X25519) recipient, writes a fresh header,
and **copies the payload byte-for-byte** — no decrypt/re-encrypt. The result is a
standard age file: `age -d export.age > a.zip && unzip a.zip`. See `AgeExporter.kt`,
`age/AgeRecipients.kt`, and `createEncryptedArchive` in `AcquisitionDetailScreen.kt`.

---

## The age implementation (BouncyCastle only)

`org.osservatorionessuno.qf.crypto.age` is a small, from-scratch age v1
implementation on a single backend. It exists because the previous library
(kage) pulled three extra deps, mixed crypto backends, buffered the whole
ciphertext on decrypt (OOM), hid the header builder (forcing reimplementation),
and offered only a pull-based encryptor.

What we implement is **format framing only** — the primitives are all
BouncyCastle (`AgePrimitives.kt`):

- Payload: ChaCha20-Poly1305 STREAM (per-chunk AEAD, truncation-resistant).
- File key: 16 random bytes per archive.
- KDFs: HKDF-SHA256 (header/payload), scrypt (passphrase export).
- Header MAC: HMAC-SHA256, **verified in constant time on decrypt** (`AgeFormat.verifyMac`).
- Recipients: custom `bugbane-*` (KeyVault), `X25519`, `scrypt`.

Robustness: the header parser (`AgeFormat.parse`) enforces hard bounds (max line,
stanza count, body size) and only ever throws `AgeFormatException` — a hostile
file cannot OOM or panic.

Interop is verified against the **stock `age` CLI** and bidirectionally against
kage (see tests). The on-disk format is plain age + ZIP, openable by standard
tooling.

---

## Guarantees & limitations

**Holds**
- Acquisition artifacts never hit disk as plaintext (streamed compress→encrypt).
- Authenticated end to end: KEK-GCM tag on the file key + per-chunk Poly1305 +
  header HMAC; tampering/truncation is detected.
- File key wrapped by a **non-exportable** StrongBox/TEE key → the archive can't
  be decrypted offline without the device's hardware key.
- Bounded-memory read and seek regardless of archive size; standard-tool exports.

**Known limitations (by design / TODO)**
- **No per-use authentication yet.** The KEK uses
  `setUserAuthenticationRequired(false)` — so on a *live, unlocked, rooted*
  device the key can be used. This protects offline at-rest, not a running
  compromised device. Per-use `BiometricPrompt` is the planned follow-up.
- **Plaintext sidecars:** `acquisition.json` (metadata) and `analysis/*.json`
  (scan results) are not encrypted (only FBE-protected by the OS).
- **Export secrecy = passphrase strength.** A shared export is protected only by
  the (generated, high-entropy) passphrase + scrypt; the device KEK is not
  involved by design.
- File key / decrypted plaintext are not zeroized in the JVM heap.

---

## Tests

- `crypto/src/test/.../age/AgeTest.kt` — round-trips at every chunk boundary,
  X25519 + scrypt interop both directions vs kage, MAC tampering rejected,
  malformed input rejected, and a dump for a stock `age` CLI check.
- `crypto/src/test/.../EncryptedArchiveTest.kt` — streaming round-trip, "payload
  is a standard ZIP / no plaintext", compression, verbatim + passphrase export.
- `crypto/src/test/.../SeekableArchiveTest.kt` — single-file seek; agreement with
  the sequential reader.
- `app/src/test/.../qf/crypto/LibmvtIntegrationTest.kt` — real libMVT analyzing
  artifacts straight from the encrypted archive.

Run: `./gradlew :crypto:test` (pure JVM) and
`./gradlew :app:testBetaDebugUnitTest --tests "*LibmvtIntegrationTest*"`.

---

## Dependencies

- **BouncyCastle** (`bcprov-jdk15to18`) — the single crypto backend.
- **Apache Commons Compress** (`1.25.0`, standalone/pure-Java) — only as a robust,
  ZIP64-aware reader for random-access seek.
- `java.util.zip` (platform) — ZIP writing and sequential reading.
- The Android Keystore (platform) — holds the KEK.

`kage` is **not** a runtime dependency; it remains only as a test-time interop
oracle in `:crypto`.
