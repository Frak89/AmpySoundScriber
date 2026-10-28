# AmpySoundScriber

Android app per trascrizione musicale e spartiti.

## Stato repository

Il workflow GitHub Actions per compilare un APK debug Android è presente in `.github/workflows/android-apk.yml`.

**I sorgenti Android non sono ancora stati caricati:** il progetto completo si trova nell'archivio locale `AmpySoundScriber-0.1.0-Android-BuildReady.zip`. Occorre estrarre il contenuto della cartella `AmpySoundScriber/` nella radice della repository prima di eseguire il workflow.

Una volta presenti `gradlew`, `settings.gradle.kts`, `app/` e `modules/`, eseguire il workflow "Build AmpySoundScriber APK" dalla sezione Actions. Dopo una compilazione riuscita, l'APK si trova tra gli artifacts.
