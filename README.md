# Card Scanner & Tracker

An automated trading card scanner that extracts card names and serial numbers (optimized for Weiss Schwarz cards), manages digital inventory, filters collected data, and exports analysis to Excel/CSV.

## Key Features

- **High-Resolution Card Scanning**: Captures high-resolution images using the Android camera and corrects orientation automatically to maximize OCR and visual processing accuracy.
- **Multimodal AI OCR Recognition**: Integrates Google Vision OCR combined with the powerful Google Gemini API model (`gemini-2.5-flash`) as a fall-back list of candidate models to extract names, traits, and strict serial formats (e.g. `BD/W125-024 CR`).
- **Inventory & Collection Management**: View, filter, search, and manage scanned trading cards with high-fidelity attributes.
- **Data Export**: Export your scanned card archive to standard spreadsheet formats (CSV/Excel compatible) for secondary use.

## Technical Highlights

- **Jetpack Compose**: Modern Material 3 responsive interface utilizing best-in-class components.
- **Robust Image Flow**: Fully supports custom resizing constraints (constrained up to 1600px width/height) to control payload sizes while maintaining perfect visual quality.
- **Local Persistence & States**: Uses clean ViewModel flow with local state flows for robust tracking of scanning tasks.

## Setup Requirements

Register and secure your Gemini API Key in your workspace or platform configs. Ensure dynamic configuration is established to inject `GEMINI_API_KEY` to the application environment.

## Contributors

Special thanks to the contributors of this project:

- **[idmakers](https://github.com/idmakers)** — Lead Creator, System Architect & Designer.
- **Google AI Studio** — AI Assistant, Co-creator, and Core Engine Developer.
