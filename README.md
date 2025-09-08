# TurnMeInto2 - AI Photo Transformation App

An Android application built with Kotlin and Jetpack Compose that allows users to capture or select photos and transform them into creative characters and scenarios using OpenAI's advanced image editing capabilities.

## Features

### 📸 Image Capture & Selection
- **Camera Integration**: Take photos directly using the device camera with CameraX
- **Gallery Access**: Select existing photos from the device gallery
- **Permission Management**: Proper camera and storage permission handling with Accompanist

### 🤖 AI Image Transformation
- **OpenAI Integration**: Real image editing using OpenAI's GPT-Image-1 model
- **Category-Based Prompts**: Organized transformation options across 12 categories:
  - 🎭 **Cartoon** - Rick & Morty, Family Guy, Simpsons, Lego characters
  - 🎬 **Movie/TV** - Game of Thrones, Harry Potter, Stranger Things, Peaky Blinders
  - 🏛️ **Historic** - Roman gladiators, knights, pirates, Victorian characters
  - 🧙 **Fantasy** - Scary clowns, Minecraft characters, Thundercats
  - 🧸 **Toy** - Action figures, bobbleheads, Toby jugs
  - 🎨 **Face Paint** - Tiger, superhero designs
  - 🐾 **Animal** - Dogs, cats, dinosaurs, meerkats, axolotls
  - 👑 **Princess** - Various princess transformations
  - 👻 **Ghost/Monster** - Frankenstein, photorealistic ghosts
  - ⚽ **Sports** - Soccer players, wrestlers, racing drivers
  - 🎭 **Other** - Cowboys, flight attendants, cabaret singers
  - 🖼️ **Art** - Famous paintings like Mona Lisa, Starry Night, The Scream
- **Smart Selection**: Two-tier dropdown system (category → specific prompt)
- **Custom Prompts**: Enter personalized transformation requests
- **Real-time Processing**: Live AI image editing with progress indicators
- **Image Comparison**: Side-by-side view of original and transformed images with horizontal pager
- **Fullscreen View**: Tap to view images in fullscreen with gesture controls
- **Save to Gallery**: Save transformed images directly to device gallery
- **Share Images**: Share transformed images using Android's native share sheet

### ⚙️ Advanced Settings
- **Image Processing Options**:
  - Downsize Images: Toggle for smaller file sizes and faster processing
  - Input Fidelity: Choose between Standard and High detail preservation
  - Output Quality: Fast, Balanced, or Best options for processing time vs. quality
- **Processing Controls**:
  - Progress indicators with detailed status messages
  - Screen-on lock during processing
  - Cancellable operations
- **Custom Commands**: Quick actions using "/" commands in the prompt field

### ✏️ Prompts Management
- **Prompt Editor**: Comprehensive in-app editor for customizing all prompts
- **Add Categories**: Create unlimited custom prompt categories
- **Add/Edit Prompts**: Modify existing prompts or create completely new ones
- **Delete Prompts**: Remove unwanted prompts with confirmation dialogs
- **Persistent Storage**: All changes saved automatically using SharedPreferences
- **Reset to Defaults**: Option to restore original prompt library
- **Intuitive Interface**: Card-based layout with clear edit/delete controls
- **Real-time Updates**: Changes appear immediately in the main prompt selection

### 🎨 Modern UI
- **Material Design 3**: Modern, accessible interface with experimental APIs
- **Jetpack Compose**: Declarative UI framework with smooth animations
- **Category Selection**: Organized dropdown for easy prompt discovery
- **Processing Overlay**: Visual feedback with spinner during AI operations
- **Responsive Design**: Optimized for various screen sizes with smart button layouts
- **Loading States**: Progress indicators and comprehensive error handling
- **Dark/Light Theme**: Automatic theme adaptation
- **Share Integration**: Native Android share sheet for social media and messaging apps

## Technical Stack

- **Language**: Kotlin 1.9.20
- **UI Framework**: Jetpack Compose with Material Design 3
- **Build System**: Gradle 8.4.0 with Android Gradle Plugin 8.4.0
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **AI Integration**: OpenAI API with custom Retrofit implementation
- **Image Processing**: ARGB_8888 bitmap handling with PNG format preservation

## Key Dependencies

```gradle
// Core dependencies
androidx.core:core-ktx:1.12.0
androidx.lifecycle:lifecycle-runtime-ktx:2.7.0
androidx.activity:activity-compose:1.8.2

// Compose BOM for consistent versions
androidx.compose:compose-bom:2023.10.01

// Camera functionality
androidx.camera:camera-camera2:1.3.1
androidx.camera:camera-lifecycle:1.3.1
androidx.camera:camera-view:1.3.1

// Image loading with data URL support
io.coil-kt:coil-compose:2.5.0
androidx.exifinterface:exifinterface:1.3.6

// Navigation
androidx.navigation:navigation-compose:2.7.6

// OpenAI API integration
com.squareup.retrofit2:retrofit:2.9.0
com.squareup.retrofit2:converter-gson:2.9.0
com.google.code.gson:gson:2.10.1
com.squareup.okhttp3:okhttp:4.12.0

// Permissions handling
com.google.accompanist:accompanist-permissions:0.32.0
```

## Project Structure

```
app/src/main/java/com/photoai/app/
├── MainActivity.kt                 # Main entry point with Compose setup
├── api/
│   └── OpenAIService.kt           # Custom OpenAI API integration
├── data/
│   └── PromptsData.kt            # Prompt categories and flexible format
├── ui/
│   ├── screens/
│   │   ├── MainScreen.kt         # Primary UI with camera and gallery
│   │   ├── EditScreen.kt         # Image editing interface with real-time preview
│   │   ├── SettingsScreen.kt     # App settings and processing options
│   │   ├── ResultScreen.kt       # Transformed image display and sharing
│   │   ├── PromptsEditorScreen.kt # Prompt management interface
│   │   ├── LandingScreen.kt      # App entry point and navigation
│   │   └── FullScreenImageDialog.kt # Fullscreen image viewer
│   ├── viewmodel/
│   │   └── MainViewModel.kt      # State management and business logic
│   └── theme/                    # Material Design 3 theming
├── utils/
│   ├── FileUtils.kt             # Image processing utilities
│   └── PromptsLoader.kt         # Prompt management system
└── res/
    ├── raw/
    │   └── prompts.json         # Default prompt library
    └── ...                      # Other resources
```

## Configuration

### Environment Variables
Set your OpenAI API key as an environment variable:
```bash
export OPENAI_API_KEY="your-openai-api-key-here"
```

### Gradle Configuration
The app automatically includes the API key in the build configuration through `local.properties`:
```properties
OPENAI_API_KEY=your-openai-api-key-here
```

## Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 34
- Kotlin 1.9.20

### Setup
1. Clone the repository
2. Set up your OpenAI API key in `local.properties`
3. Open in Android Studio
4. Sync project with Gradle files
5. Run on device or emulator

### Using the Settings
1. Access settings via the gear icon in the top bar
2. Configure image processing options:
   - Toggle "Downsize Images" for faster processing
   - Choose Input Fidelity (Standard/High)
   - Select Output Quality (Fast/Balanced/Best)
3. Access prompt management through "Edit Predefined Prompts"

### Using the Image Editor
1. Select or capture an image
2. Choose a prompt category and specific transformation
3. Enter custom prompts or use predefined ones
4. View real-time preview with horizontal pager
5. Toggle fullscreen mode by tapping the image
6. Use the floating action button to apply transformations

## Architecture

### MVVM Pattern
- **MainViewModel**: Manages UI state and coordinates with OpenAI service
- **OpenAIService**: Handles API communication with custom Retrofit implementation
- **Screen Components**: Modular UI with dedicated responsibility areas
- **PromptsLoader**: JSON resource management with caching

### Image Processing Pipeline
1. **Capture/Selection**: Camera or gallery image acquisition
2. **Format Conversion**: ARGB_8888 bitmap creation for RGBA support
3. **Resize & Optimize**: Optional downsizing based on settings
4. **API Upload**: Multipart form data with PNG preservation
5. **Response Handling**: Base64 to data URL conversion
6. **Display**: Coil-based image rendering with comparison view
7. **Share Processing**: JPEG conversion and FileProvider-based sharing

### OpenAI Integration
- **Model**: GPT-Image-1 for image editing capabilities
- **API**: Custom Retrofit interface with optimized timeouts
- **Format**: Base64 JSON response with PNG preservation
- **Error Handling**: Comprehensive error management and retry logic

## Development Notes

### Performance Optimizations
- **Configurable Image Processing**: User-controlled quality vs. speed tradeoffs
- **Screen Lock**: Prevents sleep during processing
- **Memory Management**: Efficient bitmap handling and recycling
- **Cached Prompts**: Fast loading of transformation options

### Future Enhancements
- [ ] Batch processing capabilities
- [ ] Advanced mask-based editing
- [ ] Cloud storage integration
- [ ] Offline processing options
- [ ] Multi-language support
- [ ] Enhanced sharing options
- [ ] User profiles and favorites
- [ ] Community features

## Build & Run

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Run Tests
```bash
./gradlew test
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is open source and available under the [MIT License](LICENSE).

## Support

For questions or issues, please open a GitHub issue or contact the development team.
