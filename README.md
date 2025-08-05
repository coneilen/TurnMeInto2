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
- **Image Comparison**: View original and transformed images with toggle
- **Fullscreen View**: Tap to view images in fullscreen with gesture controls
- **Save to Gallery**: Save transformed images directly to device gallery

### ✏️ **NEW! Editable Prompts System**
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
- **Responsive Design**: Optimized for various screen sizes
- **Loading States**: Progress indicators and comprehensive error handling
- **Dark/Light Theme**: Automatic theme adaptation

## Technical Stack

- **Language**: Kotlin 1.9.20
- **UI Framework**: Jetpack Compose with Material Design 3
- **Build System**: Gradle 8.7 with Android Gradle Plugin 8.4.0
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **AI Integration**: OpenAI API with custom Retrofit implementation
- **Image Processing**: ARGB_8888 bitmap handling with PNG format preservation

## Key Dependencies

```gradle
// Compose BOM for consistent versions
androidx.compose:compose-bom:2023.10.01

// Camera functionality
androidx.camera:camera-camera2:1.3.1
androidx.camera:camera-lifecycle:1.3.1
androidx.camera:camera-view:1.3.1

// Image loading with data URL support
io.coil-kt:coil-compose:2.5.0

// OpenAI API integration
com.squareup.retrofit2:retrofit:2.9.0
com.squareup.retrofit2:converter-gson:2.10.1
com.squareup.okhttp3:okhttp:4.12.0

// Permissions handling
com.google.accompanist:accompanist-permissions:0.32.0
```

## Project Structure

```
app/src/main/java/com/photoai/app/
├── MainActivity.kt                 # Main entry point with Compose setup
├── api/
│   └── OpenAIService.kt           # Custom OpenAI API integration with gpt-image-1
├── data/
│   ├── PromptsData.kt             # Data classes for prompt categories and flexible format
│   └── FlexiblePromptsData.kt     # Editable prompts data structure
├── ui/
│   ├── screens/
│   │   ├── MainScreen.kt          # Primary UI with camera, gallery, and AI editing
│   │   ├── PromptsEditorScreen.kt # **NEW!** Comprehensive prompts editor interface
│   │   └── FullscreenImageDialog.kt # Fullscreen image viewer
│   ├── viewmodel/
│   │   └── MainViewModel.kt       # State management and business logic
│   └── theme/                     # Material Design 3 theming
├── utils/
│   ├── FileUtils.kt              # Image processing and file utilities
│   └── PromptsLoader.kt          # JSON resource + SharedPreferences management
└── res/
    ├── raw/
    │   └── prompts.json          # Default prompt library (12 categories, 80+ prompts)
    └── ...                       # Other resources (strings, themes, manifests)
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
- Kotlin 1.9.10+

### Setup
1. Clone the repository
2. Set up your OpenAI API key in `local.properties`:
   ```properties
   OPENAI_API_KEY=your-openai-api-key-here
   ```
3. Open in Android Studio
4. Sync project with Gradle files
5. Run on device or emulator

### Using the Prompts Editor
1. **Access Editor**: Tap "Edit Prompts" button in the main screen header
2. **Browse Categories**: View all prompt categories in organized cards
3. **Add Category**: Tap the "+" icon in the header to create new categories
4. **Add Prompts**: Tap the "+" icon on any category card to add new prompts
5. **Edit Prompts**: Tap the edit icon next to any prompt to modify it
6. **Delete Prompts**: Tap the delete icon to remove prompts (with confirmation)
7. **Auto-Save**: All changes are automatically saved and persist between app sessions

### Permissions
The app requires the following permissions:
- `CAMERA` - For taking photos
- `READ_EXTERNAL_STORAGE` - For gallery access (API < 33)
- `READ_MEDIA_IMAGES` - For gallery access (API 33+)
- `INTERNET` - For OpenAI API calls

## Architecture

### MVVM Pattern
- **MainViewModel**: Manages UI state and coordinates with OpenAI service
- **OpenAIService**: Handles API communication with custom Retrofit implementation
- **MainScreen**: Declarative UI with category-based prompt selection
- **PromptsLoader**: JSON resource management with caching for prompt categories

### Prompt Management System
1. **Default JSON Resource**: Centralized `prompts.json` with 12 organized categories
2. **Editable Storage**: SharedPreferences-based storage for user customizations
3. **Data Migration**: Automatic conversion from static to editable format
4. **Type Safety**: `PromptsData.Prompt` and `FlexiblePromptsData` structures
5. **Caching**: Efficient loading and caching of prompt data
6. **UI Integration**: Two-tier dropdown system (category → prompt selection)
7. **Live Editing**: Real-time prompt editor with add/edit/delete capabilities

### Image Processing Pipeline
1. **Capture/Selection**: Camera or gallery image acquisition
2. **Format Conversion**: ARGB_8888 bitmap creation for RGBA support
3. **Resize & Optimize**: Half-size reduction with minimum 64x64 constraint
4. **API Upload**: Multipart form data with PNG preservation
5. **Response Handling**: Base64 to data URL conversion
6. **Display**: Coil-based image rendering with fallback support

### OpenAI Integration
- **Model**: GPT-Image-1 for image editing capabilities
- **API**: Custom Retrofit interface matching OpenAI specification
- **Timeouts**: Configured for large image uploads (30s connect, 60s write, 120s read)
- **Format**: Base64 JSON response with PNG preservation

## Development Notes

### Architecture Decisions
- **Single Activity**: Uses Jetpack Compose with single Activity architecture
- **Custom OpenAI Integration**: Built custom Retrofit service after library compatibility issues
- **MVVM Pattern**: Clear separation of UI, business logic, and data layers
- **Hybrid Prompt Storage**: JSON resources for defaults + SharedPreferences for user edits
- **Navigation State**: Composable-based navigation without Navigation Component
- **State Management**: Compose state with proper lifecycle handling
- **Image Format**: RGBA PNG preservation throughout the processing pipeline
- **Editable Prompts**: Map-based flexible data structure for unlimited customization

### Known Issues & Solutions
- **Data URL Display**: Implemented fallback temp file conversion for Coil compatibility
- **API Timeouts**: Custom timeout configuration for large image uploads
- **Memory Management**: Proper bitmap recycling and cleanup
- **Experimental APIs**: Material 3 dropdown requires `@OptIn(ExperimentalMaterial3Api::class)`

### Performance Optimizations
- **Image Resizing**: Half-size reduction before API upload
- **Memory Efficiency**: Immediate bitmap recycling after use
- **API Efficiency**: Direct byte array upload instead of temp files
- **UI Responsiveness**: Coroutine-based async operations

## Implemented Features ✅

### Core Features
- [x] Camera and gallery integration with permissions
- [x] OpenAI GPT-Image-1 integration with custom API
- [x] Category-based prompt organization (12 categories, 80+ prompts)
- [x] Two-tier dropdown selection system
- [x] Real-time image transformation with processing indicators
- [x] Custom prompt input capability
- [x] Image comparison (original vs transformed) with toggle
- [x] Save to gallery functionality

### UI/UX Features  
- [x] Material Design 3 UI with ExperimentalMaterial3Api
- [x] Fullscreen image viewer with tap-to-dismiss
- [x] Processing spinner overlay during AI operations
- [x] Comprehensive error handling and loading states
- [x] Responsive design for various screen sizes

### **NEW! Prompts Editor Features**
- [x] **Complete prompts editor interface** with card-based layout
- [x] **Add new categories** with custom names
- [x] **Add/edit/delete prompts** with real-time updates
- [x] **Persistent storage** using SharedPreferences
- [x] **Migration system** from static JSON to editable format
- [x] **Reset to defaults** functionality
- [x] **Navigation integration** with "Edit Prompts" button

### Technical Features
- [x] RGBA PNG format preservation
- [x] Data URL and base64 handling
- [x] Proper state management with Compose
- [x] Memory-efficient bitmap handling
- [x] Custom timeout configuration for API calls
- [x] Flexible data structures for prompt management

### Future Enhancements
- [ ] Prompt sharing and community features
- [ ] Export/import custom prompt collections
- [ ] Prompt templates and variables
- [ ] Image history and transformation caching
- [ ] Batch image processing capabilities
- [ ] Advanced editing options (masks, variations, style transfer)
- [ ] Social sharing and export features
- [ ] User preferences and favorites system
- [ ] Offline mode with local ML models
- [ ] Cloud storage integration and sync
- [ ] Performance optimizations for large images
- [ ] Multi-language prompt support
- [ ] Advanced prompt validation and suggestions

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
