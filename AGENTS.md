# Repository Guidelines

## Project Structure & Module Organization
Gradle drives builds through `build.gradle` plus helper scripts in `gradle/`. Plugin sources reside under `src/main/java/net/runelite/client/plugins/microbot/<PluginName>` with matching resources in `src/main/resources/<PluginName>`. Each plugin should also keep documentation inside `src/main/resources/<PluginName>/docs/` (README, optional `assets/`, and `dependencies.txt`). Shared web assets live in `public/`, while IDE/debug helpers and tests sit in `src/test/java`, notably `net.runelite.client.Microbot` for RuneLiteDebug sessions.

## Build, Test, and Development Commands
Use `./gradlew clean build` for a full compile, shading, and plugin detection report. During iteration, limit the scope via `./gradlew build -PpluginList=DailyTasksPlugin`. Execute unit tests with `./gradlew test`, and launch the client for manual verification through `./gradlew run --args='--debug'`.

## Coding Style & Naming Conventions
Java code uses four-space indentation, Lombok annotations where they reduce boilerplate, and `PluginDescriptor` metadata referencing constants from `PluginConstants`. Keep plugin versions in a `static final String version = "x.y.z"` field. Packages remain lowercase, while class names mirror plugin folders in UpperCamelCase (e.g., `PestControlPlugin`). Runtime assets stay under each plugin’s resources subtree, and documentation should reuse existing tag constants.

## Testing Guidelines
Place unit tests under `src/test/java` (or `src/test/generated_tests` for generated fixtures) with a `*Test` suffix. Tests should exercise automation logic headlessly, using deterministic mocks instead of live client calls. Add your plugin to `RuneLiteDebug.pluginsToDebug` in `src/test/java/net/runelite/client/Microbot.java` when interactive checks are necessary.

## Commit & Pull Request Guidelines
Follow the conventional subject style, e.g., `feat: add PestControlPlugin (#241)`. Keep subjects under ~72 characters and mention the plugin or subsystem touched. Pull requests must link related issues, summarize behavior changes, list the commands used for verification (`./gradlew clean build`, `./gradlew test`, etc.), and include screenshots or GIFs for UI overlays. Limit each PR to a single plugin or feature and call out dependency or configuration updates explicitly.

## Security & Configuration Tips
Never commit credentials; load secrets from local `gradle.properties`. Prefer released dependency versions and justify additions inside each plugin’s `docs/README.md`. Use `PluginConstants.DEFAULT_PREFIX` and `DEFAULT_ENABLED`, and confirm `minClientVersion` against the Microbot client version logged during builds.
