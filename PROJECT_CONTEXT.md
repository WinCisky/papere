# Project Context: Papere

## Overview
**Papere** is an Android application designed to automatically and periodically update the device wallpaper. It fetches high-quality, random images from Wikimedia Commons based on user-defined categories (macros) and applies them to the system wallpaper.

The app is built with a focus on efficiency and reliability, using `WorkManager` for background tasks and respecting device constraints like network type (WiFi/Cellular) and battery level.

## Core Features
- **Automatic Wallpaper Rotation**: Uses Android's `WorkManager` to schedule periodic background tasks that download and set new wallpapers without user intervention.
- **Wikimedia Commons Integration**: Leverages the Wikimedia API to browse categories and fetch high-resolution images.
- **Smart Selection**: Implements logic to skip low-resolution images (minimum 2000x2000 pixels) to ensure wallpaper quality.
- **Customizable Constraints**:
    - **Network Requirement**: Option to only download wallpapers when connected to WiFi to save data.
    - **Battery Awareness**: Ability to postpone updates if the battery level is below a certain threshold (e.g., 50%).
    - **Update Frequency**: Users can configure how often the wallpaper changes.
- **Image Processing**: Automatically crops downloaded images to match the device's specific screen dimensions and aspect ratio.
- **Attribution Support**: Displays attribution information (Author/License) for the current wallpaper.
- **Service Management**: Simple UI to start, stop, and monitor the background worker status.

## Tech Stack
- **Language**: Kotlin
- **Platform**: Android (Targeting modern API levels, e.g., 36)
- **Key Libraries**:
    - `androidx.work:work-runtime-ktx`: For robust background task scheduling via `WorkManager`.
    - `okhttp3`: For performing network requests to the Wikimedia API and downloading image data.
    - `AndroidX AppCompat` & `Material Components`: For a modern Android UI.
    - `ViewBinding`: For type-safe interaction with UI components.

## Key Components

### Source Code Structure (`com.ssimo.papere`)
- **`MainActivity.kt`**: 
    - The primary user interface.
    - Displays the current wallpaper preview.
    - Shows real-time status of the `WallpaperWorker` (Active/Inactive).
    - Shows the time remaining until the next scheduled update.
    - Handles permission requests (e.g., `SET_WALLPAPER`, `POST_NOTIFICATIONS`).
    - Provides controls to toggle the background service on/off and access customization.
- **`WallpaperWorker.kt`**: 
    - A `CoroutineWorker` that contains the core business logic.
    - Performs the following steps in the background:
        1. Checks battery and network constraints.
        2. Queries Wikimedia API for random images within selected categories.
        3. Downloads the chosen image.
        4. Processes/crops the bitmap to fit the device screen.
        5. Sets the new image as the system wallpaper.
        6. Schedules the next execution of the worker.
- **`CustomizeActivity.kt`**: 
    - (Inferred) UI for managing user preferences such as WiFi requirement, battery threshold, and category selection.
- **`Logger.kt`**: 
    - A utility class for consistent logging throughout the application lifecycle.
- **`Constants.kt`**: 
    - Holds shared constants like worker names, unique work identifiers, and other static configuration values.

## Important Implementation Details
- **Data Persistence**: Uses `SharedPreferences` (`wallpaper_prefs`) to store user settings (WiFi requirement, battery level), image metadata (attribution), and scheduling information (next execution time).
- **Error Handling**: The `WallpaperWorker` includes a retry mechanism with failure counts. After a set number of consecutive failures, it stops retrying and notifies the user via a system notification.
- **Screen Dimensions**: The app saves the device's screen width and height in `SharedPreferences` during the first run (or when triggered) to ensure optimal image cropping for subsequent updates.

## Dependencies Summary
(Extracted from `app/build.gradle.kts`)
- `androidx.core:core-ktx`
- `androidx.appcompat:appcompat`
- `com.google.android.material:material`
- `androidx.work:work-runtime-ktx`
- `com.squareup.okhttp3:okhttp`
