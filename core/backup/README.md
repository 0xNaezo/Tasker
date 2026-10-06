# core:backup

Export, daily backups, Auto Backup rules and restore (tech plan §16, DATA-1, NFR «Сохранность»).

| Piece | What it does |
| --- | --- |
| `ExportService` | `exportTo(uri)` writes a ZIP with `export.json` (`DataExporter.encode`) and `tasks.md` (`MarkdownExporter`); `importFrom(uri, replace)` reads an export ZIP, a bare `export.json` or a backup `*.json.gz` |
| `BackupService`, `BackupWorker`, `BackupScheduler` | daily `files/backups/backup-YYYY-MM-DD.json.gz` (gzip of `export.json`), rotation 7 daily + 4 weekly (`BackupRotation`), `latest.json.gz` for Auto Backup, optional copy into the folder in `AppSettings.backupTreeUri` |
| `RestoreService` | on first start with an empty database: `findRestorable()` → "Restore data from <day>?" → `restore(candidate)` |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Auto Backup and device transfer carry only `backups/latest.json.gz` and `datastore/settings.json`, never the live database |

Import and restore never lose data silently: a database with data is replaced only with `replace = true`, the current
data is saved as today's backup first, and if the new data cannot be stored the previous data is put back.

## Wiring in the app

**Manifest** (`app/src/main/AndroidManifest.xml`, `<application>`):

```xml
<application
    android:allowBackup="true"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules"
    tools:targetApi="31"
    ...>
```

`fullBackupContent` applies up to Android 11, `dataExtractionRules` from Android 12. Auto Backup has a 25 MB quota per
app. Gzipped JSON is about a tenth of its raw size and stays at a few megabytes even after years of use, but eleven
rotated copies would not; that is why only `latest.json.gz` is included.

**WorkManager with Hilt.** `BackupWorker` is a `@HiltWorker`, so the `Application` must provide the Hilt worker factory
and the default initializer must be removed:

```kotlin
@HiltAndroidApp
class TaskerApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

**On every start:** `BackupScheduler.schedule()` (idempotent, keeps an existing schedule). Before onboarding, call
`RestoreService.findRestorable()`; if it returns a candidate, ask once "Restore data from `candidate.summary.day`?" and
call `restore(candidate)`. After a restore or an import, run the catch-up pass and reschedule reminders as on start.

**Settings → Data:**

- Export: `rememberLauncherForActivityResult(CreateDocument(ExportService.MIME_TYPE))`, launch with
  `exportService.exportFileName()`, pass the Uri to `exportTo`.
- Import: `OpenDocument` with `arrayOf("application/zip", "application/json", "application/gzip",
  "application/octet-stream")`; `importFrom(uri)`; on `ImportResult.NotEmpty` confirm "Replace all data?" and call
  `importFrom(uri, replace = true)`; show `ImportResult.Invalid.problem`.
- Backup folder: `OpenDocumentTree`, then `contentResolver.takePersistableUriPermission(uri, READ or WRITE)`, save
  `backupTreeUri`, and call `BackupService.backUp()` to copy right away. `BackupResult.Written.folder` is
  `FolderCopy.Failed` when the folder is no longer reachable (for example, settings restored on a new phone).
- "Last backup": `BackupService.backups().firstOrNull()?.day`.
