# Signing keys

## `ci.keystore` — convenience key for CI builds

The GitHub Actions workflow signs release APKs with this committed keystore
when no real signing secrets are configured. It exists so that:

- every CI build installs over the previous one (stable signature), and
- you can grab an APK straight from a release and install it.

It is a **throwaway key stored in a public repo** — treat builds signed by it
as personal conveniences, not as authenticated releases. Alias and passwords
are `bluedrop-ci` / `bluedrop-ci` (also baked into `app/build.gradle.kts`).

## Switching to a real release key (recommended once you share builds)

1. Generate a private keystore and keep it OUT of the repo:

   ```
   keytool -genkeypair -v -keystore bluedrop-release.keystore \
     -alias bluedrop -keyalg RSA -keysize 4096 -validity 10950
   ```

2. Base64 it: `base64 -w0 bluedrop-release.keystore > keystore.b64`

3. Add four secrets to the GitHub repo (Settings → Secrets and variables →
   Actions):

   | Secret | Value |
   | --- | --- |
   | `ANDROID_KEYSTORE_BASE64` | contents of `keystore.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
   | `ANDROID_KEY_ALIAS` | e.g. `bluedrop` |
   | `ANDROID_KEY_PASSWORD` | the key password |

The workflow decodes the keystore and writes a temporary `local.properties`,
which `app/build.gradle.kts` prefers over `ci.keystore`. Locally, the same
override works via `local.properties` keys `STORE_FILE` / `STORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD`.
