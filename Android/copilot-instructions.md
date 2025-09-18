<!-- Use this file to provide workspace-specific custom instructions to Copilot. For more details, visit https://code.visualstudio.com/docs/copilot/copilot-customization#_use-a-githubcopilotinstructionsmd-file -->

# Photo AI Android App Instructions

This is an Android application built with Kotlin and Jetpack Compose that allows users to:

## Features
- Take photos using the device camera
- Select photos from the device gallery
- Apply predefined AI prompts to analyze images
- Enter custom prompts for image analysis
- Display AI responses in a user-friendly interface

## Architecture
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Build System**: Gradle
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

## Key Dependencies
- Jetpack Compose BOM for UI components
- CameraX for camera functionality
- Coil for image loading
- Accompanist for permissions handling
- Navigation Compose for screen navigation

## Project Structure
- `MainActivity.kt`: Main entry point with Compose setup
- `MainScreen.kt`: Primary UI screen with camera, gallery, and AI prompt functionality
- `FileUtils.kt`: Utility functions for file handling
- `ui/theme/`: Material Design 3 theming components

## Development Notes
- Uses Material Design 3 theming
- Implements proper permission handling for camera access
- File provider configuration for camera image capture
- Responsive UI design with proper spacing and accessibility
- Error handling and loading states for AI processing

## Future Enhancements
- Integration with actual AI services (OpenAI, Google Vision, etc.)
- Image preprocessing and optimization
- Result caching and history
- Social sharing capabilities
- Advanced camera controls
