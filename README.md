# Insular

Isolate your Big Brother apps.

This is a fork of [Insular](https://gitlab.com/secure-system/Insular) (which itself is a fork of [Island](https://github.com/oasisfeng/island) by Oasis Feng), with additional features and improvements. Credit also goes to [Shelter](https://github.com/PeterCxy/Shelter) which inspired the original FLOSS fork.

## What's new in this fork

- **Custom CA certificates** — install your own CA certificates into the trust store of the work profile via `Settings → Scoped Settings → Security`, without affecting the main profile. Supports PEM and DER. The certificate is shown with its subject, issuer, expiry and SHA-256 fingerprint for confirmation before it is trusted.

  Note that a user-added CA is only honoured by apps that opt in to it — since Android 7 an app targeting API 24+ ignores user-added certificates unless its `network_security_config.xml` declares `<certificates src="user"/>`.

## Features

With Insular, you can:

- Isolate your Big Brother apps into a separate work profile
- Clone and run multiple accounts simultaneously
- Freeze or archive apps and prevent any background behaviors
- Unfreeze apps on-demand with home screen shortcuts
- Re-freeze marked apps with one tap
- Hide apps
- Selectively enable (or disable) VPN for different groups of apps
- Prohibit USB access to mitigate attacks with physical access

## Documentation

See [the documentation](https://secure-system.gitlab.io/Insular/) for setup instructions (including ADB-based activation), cross-profile file access, God mode, and differences from Island.

## Uninstalling

To remove Insular completely: go to `Settings → Scoped Settings → Destroy` and confirm. If you've already uninstalled the app, go to system `Settings → Accounts → Remove work profile`.

## Permissions

- **Device Admin** — required to create and manage the Island work profile. Explicitly requested for your consent.
- **Package Usage Stats** — required to correctly detect running state of apps. Explicitly requested for your consent.

We never collect data related to your privacy.

## Build

### Prerequisites

Clone the [deagle library](https://github.com/oasisfeng/deagle) alongside this repo:

```
\--
  |- Insular
  |- deagle
```

### Modules

The project is split into several Gradle modules, with `assembly` as the build entry point:

| Module | Purpose |
|--------|---------|
| `engine` | Core DPC engine, shares package name with the complete build to retain profile ownership |
| `mobile` | Main UI (launcher, settings, app list) |
| `shared` | Shared utilities |
| `assembly` | Build portal combining all modules via product flavors |

### Building

```bash
# Debug APK (fdroid flavor)
./gradlew :assembly:assembleCompleteFdroidDebug

# Release APK (requires signing keystore)
./gradlew :assembly:assembleCompleteFdroidRelease
```

## Contribution

Bug reports, minor improvements, and translations are welcome via pull requests. For larger features, please open an issue first to discuss.

## Open API

Island/Insular exposes DPC capabilities to third-party apps via open APIs, defined in [Api.java](/shared/src/main/java/com/oasisfeng/island/api/Api.java). Apps can request runtime permissions to leverage these APIs for freezing, launching, and managing apps in the work profile.

## License

Island and its derivatives are open source.
