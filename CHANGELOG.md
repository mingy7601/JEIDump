# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/spec/v2.0.0.html


## [1.1.0] - 2025-05-05
### Added
- Add a "last updated" timestamp to the footer, showing when the dump was generated.
- Add I18n support for the website and a language selector to switch between available translations. The command feedback messages are now internationalized as well.
- Deduplicate shared recipe backgrounds per category in exported dumps, and layer the shared background below per-recipe foreground PNGs on the website when it reduces the total image size.
- Export arbitrary JEI ingredient types, using JEI's registered ingredient helpers and renderers instead of hardcoded item and fluid handling.


## [1.0.1] - 2025-05-04
### Fixed
- Fix github link in footer.


## [1.0.0] - 2025-05-04
### Added
- Initial release of JEI Dump.
  - Exports JEI recipes into a static site with HTML, CSS, JS, and PNG assets.
  - Supports vanilla and all JEI plugins.
  - Usage instructions included in the README.