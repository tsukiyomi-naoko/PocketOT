# Pocket OT permanent Android signing

Pocket OT development APKs on `agent/pocket-ot2-v049` use a dedicated permanent signing identity.

## Public certificate identity

- Algorithm: RSA 4096 / SHA256withRSA
- Subject: `CN=Pocket OT, OU=Android, O=Pocket OT, L=Sherbrooke, ST=Quebec, C=CA`
- Certificate SHA-256: `0A:C7:BF:B8:D4:AF:7D:0C:50:F0:C5:A6:B5:27:0D:1C:C2:6A:A2:05:6E:7B:18:C4:A4:93:1C:F6:D8:6E:A3:65`

## Required GitHub Actions secrets

The private signing material must never be committed to this public repository. The signed workflow expects:

- `POCKET_OT_KEYSTORE_B64`
- `POCKET_OT_KEYSTORE_PASSWORD`
- `POCKET_OT_KEY_ALIAS`
- `POCKET_OT_KEY_PASSWORD`

Workflow: `.github/workflows/build-pocket-ot2-v049-signed.yml`

The workflow verifies the resulting APK certificate against the SHA-256 fingerprint above before publishing the artifact.
