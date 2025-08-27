# Telegram TDLib Watcher Java

Spring Boot web application wrapping TDLib via JNA. Provides a web interface for
starting and stopping chat dumps with selectable content types.

## Building

```bash
./gradlew build
```

## Running

```bash
./gradlew bootRun
```

Open <http://localhost:8080> to access the UI.

## Docker

Build the image:

```bash
docker build -t tdlib-watcher .
```

Run the container:

```bash
docker run -p 8080:8080 tdlib-watcher
```

## Database

The application reuses a single SQLite database (`tdlib.db`) and creates a dedicated
schema per chat by prefixing tables with the chat ID.
