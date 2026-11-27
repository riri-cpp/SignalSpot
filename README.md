# 📡 SignalSpot

### Cellular & Wi-Fi Signal Mapping for Android

**Find stronger signal spots. Compare carriers. Map real-world
connectivity.**

SignalSpot is an offline-first Android application designed to collect
crowdsourced cellular and connected Wi-Fi signal measurements while the
app is open, then display those readings on a color-coded map.


------------------------------------------------------------------------

## ✨ Why SignalSpot?

Mobile signal can change significantly from one street, building, or
location to another. Broad carrier coverage maps do not always show the
signal quality users experience at a specific spot.

SignalSpot aims to make local connectivity information easier to
understand by turning phone-collected measurements into a shared,
map-based view.

**Who can benefit?** - 🎓 **Students and remote workers** looking for a
reliable place for online classes, calls, and uploads. - 🏠 **Renters
and boarding-house hunters** comparing local carrier performance before
choosing a place to stay. - 🛵 **Commuters and delivery riders**
identifying useful signal spots along their routes. - 📱 **Everyday
mobile users** who need a more reliable connection for communication and
digital transactions.

## 🚀 Key Features

| Feature | What It Does |
|---|---|
| 🗺️ Signal Heatmap | Displays collected readings as color-coded map squares. |
| 📶 Automatic Signal Collection | Records cellular, Wi-Fi, and GPS-related measurements as the user moves, while the app is open. |
| 🏢 Carrier Filters | Filters map readings by carrier, including Globe, Smart, and DITO. |
| 📡 Network-Type Filters | Helps distinguish 2G, 3G, 4G, and 5G readings. |
| 📍 Nearest Strong-Signal Spot | Helps users locate the closest mapped area with strong signal. |
| ⚖️ Carrier Comparison | Lets users tap a map square and compare available carrier and Wi-Fi averages. |
| 📴 Offline-First Recording | Keeps readings collected without internet and syncs them when connectivity returns. |
| ⏯️ Pause and Resume | Gives users control over automatic collection. |
| 🔄 Network-Aware Syncing | Supports syncing when the device reconnects or switches between Wi-Fi and mobile data. |


## 🎨 Heatmap Guide

The map groups nearby readings into approximately **22 m × 22 m squares**.

| Color | Meaning |
|---|---|
| 🟩 Green | Strong signal |
| 🟨 Yellow | Fair signal |
| 🟥 Red | Weak signal or 2G-only area |

> ⚠️ **Note:** The map only displays areas where readings have been collected. It does **not** predict signal quality in places that have not yet been visited by users running the app.

## ⚙️ How It Works

1.  **Open the app** --- SignalSpot displays a map centered around the
    user's location and loads recent readings.
2.  **Walk with the app open** --- The app records signal measurements
    after the movement threshold is reached and GPS accuracy is
    sufficient.
3.  **View the heatmap** --- Readings are grouped into map squares and
    displayed by signal category.
4.  **Explore the map** --- Users can filter by carrier or network type,
    find a nearby strong-signal area, and compare readings.
5.  **Go offline?** --- New readings are saved locally while the device
    has no internet connection.
6.  **Reconnect** --- Queued readings are uploaded when Wi-Fi or mobile
    data becomes available.



## 📱 Platform & Scope

-   **Platform:** Android
-   **Minimum Android version:** Android 10 (API 29), as specified in
    the project proposal
-   **Device requirement:** A real Android phone is required for testing
    cellular signal readings.
-   **Collection behavior:** Automatic recording is intended to run only
    while the app is open on screen.
-   **Initial pilot area:** Batangas State University -- Alangilan
    Campus and nearby streets, including Golden Country Homes.
-   **Accounts:** The proposed design does not require user accounts.
-   **Maps:** Uses OpenStreetMap data through osmdroid; no paid Google
    Maps API key is planned.

## 📏 Collection Rules & Technical Targets

The following values are the proposed project targets:

| Item | Target / Rule |
|---|---|
| 🚶 Movement Interval | At least 20 meters walked between readings |
| ⏱️ Minimum Time Interval | At least 10 seconds between readings |
| 📍 GPS Accuracy | Skip recording when accuracy is worse than 30 meters |
| 🗺️ Map Square Size | Approximately 22 m × 22 m |
| 📊 Map Loading | Up to the 800 most recent readings in an area |
| 🔄 Offline Sync | Upload queued readings after reconnection |
| 🛡️ Duplicate Prevention | Give each reading a unique ID |
| 🔋 Battery-Conscious Behavior | Stop collection when the app is minimized or closed; allow manual pause |



## 🔒 Privacy, Permissions & Limitations

SignalSpot relies on Android device capabilities to collect location and
signal information. The app should request only the permissions needed
for its features and explain why they are required.

Important limitations in the proposed scope:

-   Collection stops when the app is minimized or closed; it is not
    designed for background tracking.
-   Recording pauses when GPS accuracy becomes too poor to reliably
    locate a reading.
-   Standard 2D GPS cannot distinguish different floors inside the same
    building.
-   Wi-Fi strength is collected only for the Wi-Fi network the phone is
    currently connected to.
-   Signal values may vary slightly between phone models and chipsets.
-   The app records passive signal indicators; it does not run active
    download-speed tests.
-   A map area may remain blank until users collect readings there.
-   The initial pilot data collection and user testing focus on the
    BatStateU Alangilan area.


## 🛠️ Development Setup

The exact setup steps depend on the current repository configuration. At
a minimum, development requires:

-   Android SDK with Android 10 (API 29) support or newer.
-   A compatible JDK and the Gradle version configured by the project.
-   A real Android phone for validating cellular signal readings.
-   USB debugging enabled if installing and testing through ADB.
-   Firebase project configuration for Firestore access, if cloud sync
    is enabled in the current build.

### Build and run

1.  Clone this repository.
2.  Open the Android project in your preferred compatible development
    environment.
3.  Allow Gradle to sync and download the dependencies configured by the
    project.
4.  Add the required Firebase configuration file if it is not already
    included and the app uses Firebase.
5.  Connect a real Android device with USB debugging enabled.
6.  Build and install the app using the project's Gradle wrapper or your
    IDE.

For command-line builds, the usual Gradle tasks are:

``` bash
# Windows
gradlew.bat assembleDebug
gradlew.bat installDebug

# macOS / Linux
./gradlew assembleDebug
./gradlew installDebug
```

If your local setup uses a different module name or build variant, use
the tasks defined by this repository.

## 🗂️ Project Structure

The exact folder and file structure should be documented here once the
implementation is finalized. The proposed solution separates the main
responsibilities into clear areas:

-   **UI / Map** --- Compose screen, map display, filters, and signal
    detail views.
-   **Signal collection** --- Cellular, Wi-Fi, and location measurement.
-   **Recording control** --- Movement threshold, GPS accuracy checks,
    and pause/resume behavior.
-   **Data and synchronization** --- Reading storage, Firestore access,
    offline queue, and duplicate prevention.



## 👥 Project Team

-   **Macatangay, Alwyn Kent** - Front-End Developer
-   **Pasia, Rheman**          - Back-end Developer
-   **De Castro, Aaron Rovic** - Quality-Assurance


