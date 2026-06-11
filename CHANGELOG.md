<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# R3 Book Reader Changelog

## [Unreleased]

## [0.0.4]

### Fixed

- **ToolWindow NPE**: Fixed a critical `NullPointerException` when initializing the tool window with an empty reading history.
- **Empty State UI**: Fixed an issue where the "No recent books" message was hidden by an empty scroll pane.

### Changed

- Updated supported format list in the UI to include MOBI.

## [0.0.3]

### Added

- **Full MOBI Support**: Read .mobi files with improved metadata extraction.
- **R3 Branding**: New name "R3 Book Reader" and updated visual identity.
- **New Logos**: Added official plugin icon for JetBrains Marketplace and refreshed tool window icon.
- **Cyrillic Support**: Improved encoding detection (CP1251) for MOBI files.

### Fixed

- Fixed encoding issues in MOBI titles (removed "squares" and null bytes).
- Corrected display of book names in the recent books list.

## [0.0.2]

### Added

- Expanded compatibility to all JetBrains IDEs (WebStorm, PyCharm, CLion, etc.).
- Trash icon in the recent books list to remove history entries.
- Automatic closing of editor tabs when a book is removed from history.

### Fixed

- Fixed book list jumping when updating reading progress.
- Improved PDF scroll position saving and restoration.
- Updated book icon to better match IntelliJ system style and brightness.
- Fixed NullPointerException in tool window interaction.

### Removed

- Removed non-functional MOBI format support.

## [0.0.1]

### Added

- Initial release.
- Support for EPUB, FB2, and PDF formats.
- Night mode support.
- Recent books tracking.
