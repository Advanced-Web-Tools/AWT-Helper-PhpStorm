# Verification report

Environment: Linux, 2026-10-08. Build JVM: Azul JDK 21; compilation target: Java 17. PHP CLI used for executable contract tests: PHP 8.4.26 with `zip`, `curl`, PDO MySQL and readline available.

## Executed

The final command was:

```sh
./gradlew test buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PawtPhp=/usr/bin/php -PawtFixtures=/tmp/awt-research
```

The fixture directory contains the actual downloaded published assets identified in [UPSTREAM.md](UPSTREAM.md). No upstream repository was modified.

| Check | Result |
| --- | --- |
| Kotlin compile against bundled PhpStorm 2024.1 PHP/platform APIs | Passed |
| Plugin build, including headless Settings searchable-options generation | Passed |
| Gradle plugin project configuration validation | Passed |
| 10 core tests: manifest/schema/nullability, dependencies, portable names, placeholder content/path replacement, binary preservation, safe extraction/wrappers, generated layout/NAME-only changes, release selection, Build/Deploy flags and negative stdin, Run modes, URL router arguments, split-chunk secret redaction | Passed |
| PhpStorm 2024.1 indexing/configuration integration fixture | Passed |
| Original published PHP Build/Deploy contract test | Passed |
| Original published CLI parser/help test | Passed |
| Real local PHP server with URL subfolder, static asset, literal endpoint and owned-process stop | Passed |
| JetBrains Plugin Verifier 1.410: PhpStorm 2024.1, build PS-241.14494.237 | Compatible |
| JetBrains Plugin Verifier 1.410: PhpStorm 2025.3, build PS-253.28294.345 | Compatible |

Total: **14 tests, 0 failures, 0 skipped** in the executable-fixture run. Without the two optional Gradle properties, the three executable PHP tests are explicitly skipped; core and IDE integration tests still run.

The IDE fixture checks the `.awt` content exclusion, PHP external-library classification, a framework class found exactly once by `PhpIndex`, absence of a helper PHP class from that index, all three framework modes, stable generated configuration counts across repeated synchronization, and replacement of the plugin-owned mapping. A framework moved into another project subfolder gets a separate content exclusion to avoid overlapping content/library classification.

The original build runtime is executed from paths containing spaces. Build produces a ZIP, emits its real installation prompt, consumes `n` and sends no HTTP request. Deploy with `--install --fast --action=update` posts the real multipart package/secret/action to an isolated HTTP receiver. The receiver simulates the expected AWT response; it is not a live AWT installation. The IDE's `php -r ... -- <flags>` preflight launch is also checked against the original runtime to ensure PHP `getopt` sees the supported flags.

The CLI test checks original quote/backslash parsing, preservation of parameter positions and `hostname;port=3307`, and clean EOF termination of `php awt`. It does **not** run database migrations.

## Limits of the executed checks

- No MySQL server/test database is available in this workspace. A complete framework installation, migrations, live AWT database-backed page and real package installation on AWT were not exercised end to end. The plugin performs PDO connection preflight and invokes the original installer; these paths were implemented from audited release source, with executable CLI parsing/help verified.
- Windows and macOS builds/runtime were not executed. Platform-independent argument lists, Java file APIs, native PHP executable selection and filesystem permission checks are implemented. Linux process launches with spaces were exercised.
- The full Welcome-screen wizard and browser were not manually clicked in a visible IDE. The headless minimum IDE loaded the plugin, generated its Settings search index, and ran the indexing/configuration fixture. Browser opening is tied to the owned PHP process's startup output.
- The 2025.3 check is static Plugin Verifier compatibility, not a second full IDE runtime fixture. Later IDE builds beyond the two listed versions have not been verified.
- Newer-platform verifier deprecation notices remain for the credential constructor available in the minimum SDK and the legacy parameterless `ProjectGeneratorPeer.getComponent()` required by 2024.1. These are not missing methods, internal APIs or compatibility errors. The optional API scheduled for removal was removed.
- Framework migrations can partially change an existing database before failing. File cancellation cleanup never drops database tables; manual repair of the development database may be necessary.
- GitHub future release assets may change the upstream contract. The plugin rejects missing known assets, invalid payloads and unexpected NAME definitions rather than silently downloading `main` or guessing commands.

Machine-readable/local reports are produced under `build/test-results/test`, `build/reports/tests/test`, and `build/reports/pluginVerifier`. Build outputs are ignored by Git.
