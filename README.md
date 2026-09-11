# Salaah Alarm

An Android alarm clock whose alarms are anchored to salaah times instead of to the clock.
You set "20 minutes before Fajr" once; the alarm then moves every day as the sun does.

This is **not** an athan app. It plays your alarm sound, not the adhan, and it has no
qibla compass, no Hijri calendar and no mosque finder. It is a dynamic alarm clock.

## How it works

Every alarm is stored as a rule, never as a time:

```
trigger = salaahTime(anchor, date, location) + offsetMinutes
```

Nothing is precomputed into a timetable. When an alarm fires, the receiver immediately
re-derives that rule's next occurrence and re-registers it, so the schedule tracks the
sun indefinitely without a stored calendar.

Prayer times come from [Adhan](https://github.com/batoulapps/Adhan), which implements the
astronomical equations from Jean Meeus' *Astronomical Algorithms*. They are computed
on-device: no network, no API key, and your coordinates never leave the phone.

### Features

- Multiple independent alarms, each with its own anchor, offset and weekday pattern.
- Anchor to Fajr, Sunrise, Dhuhr, Asr, Maghrib or Isha.
- Offsets from 6 hours before to 6 hours after, including exactly at the salaah.
- Per-alarm weekdays, label, vibration and snooze length.
- "Skip next" for a single occurrence without disabling the alarm.
- Real alarm behaviour: alarm-stream audio that plays through silent mode, a full-screen
  ringing screen over the lock screen, vibration, snooze and dismiss.
- Calculation method, madhab, high-latitude rule, and per-salaah minute tuning so the
  times line up with your local masjid's printed timetable.

## Design notes

**Only the next occurrence of each rule is registered with AlarmManager.** Registering a
month of firings would go stale the moment a setting or the timezone changed. Re-arming
on fire keeps exactly one source of truth.

**`setAlarmClock`, not `setExact`.** It is the only alarm API exempt from both Doze and
the exact-alarm quota, and it surfaces the system alarm icon so the user can see the
alarm is really armed.

**The day-of-week filter applies to the anchoring salaah's day, not the day the alarm
lands on.** An "Isha + 5 hours" alarm restricted to Sunday rings at 00:32 on Monday
morning, because it is Sunday's Isha alarm. This is tested.

**Prayer times can be genuinely undefined.** Above the Arctic circle in midsummer the sun
never sets, and adhan correctly returns nothing rather than inventing a time. The
scheduler skips those days and walks forward; the UI shows the real next time with a
countdown, so a Tromsø user in June sees "Sat 25 Jul · in 34d" rather than a silent
alarm that never rings.

**The search starts at yesterday**, because a positive offset can push a trigger past
midnight into today.

## Reliability on Android

An alarm clock lives or dies on whether the OS lets it run.

- `USE_EXACT_ALARM` is declared, which is granted at install time and is permitted by
  Play policy for apps whose core function is an alarm clock. `SCHEDULE_EXACT_ALARM` is
  declared as the pre-Android-14 fallback, and the UI warns and deep-links to settings if
  exact alarms are revoked.
- Alarms are re-derived on boot, package replace, timezone change, clock change, locale
  change, app start, and once every six hours as a safety net.
- Audio uses `USAGE_ALARM` so it plays through ringer-silent and sits inside Do Not
  Disturb's default "alarms" exception. A muted alarm stream is raised automatically.
- OEM battery managers (Xiaomi, Huawei, Samsung, OnePlus) will still kill background
  apps. Settings has a shortcut to the battery-optimisation screen; there is no way to
  fully solve this from inside the app.

## Verification

The prayer engine, the data model and the scheduling math have no Android dependencies,
so they are covered by plain JVM unit tests in `app/src/test`:

```
./gradlew test
```

13 tests cover ordering and plausibility of computed times, the Hanafi/standard Asr
difference, user tuning, day-to-day drift, negative and midnight-crossing offsets, the
weekday filter semantics, skip-next, and the polar-day case.

CI runs these on every push and then assembles the APK, so a green run means the whole
project — Compose UI, services, receivers and manifest included — compiles.

> **Not yet verified:** the app has never been run on a device or emulator. Compiling is
> not the same as working. The ringing path in particular (lock-screen display, audio
> through Do Not Disturb, wake locks, OEM battery managers) can only really be confirmed
> on real hardware.

Independently checked: for Riyadh on 2026-03-15 the engine gives Dhuhr 12:02, which
matches solar noon derived by hand — 12:00 − (46.6753° − 45°) × 4 min − EoT(≈ −9 min) —
and sunrise 06:03 / sunset 18:02 sit symmetrically around it four days before the
equinox, confirming the timezone handling.

Worth doing once on your own device: compare a month of computed times against
[AlAdhan](https://aladhan.com/prayer-times-api) with matching method and madhab. They
should agree to the minute.

## Getting the APK

**From a release** — easiest, no GitHub login needed:
[Releases](https://github.com/asimzf/Athan/releases) → download the `.apk` → open it on
your phone. Android will ask you to allow installing from this source.

**From a build** — the newest APK for any commit:
[Actions](https://github.com/asimzf/Athan/actions) → pick a green run → *Artifacts* →
`salaah-alarm-apk`. Downloading a workflow artifact does require being signed in to
GitHub, and it arrives as a zip.

Both are **debug** builds, signed with the `app/debug.keystore` committed to this repo,
so they install directly and — importantly — **update over each other**. They are not
Play-Store-signed, so expect the usual "unknown developer" warning.

The keystore is checked in deliberately. Android's debug keystore is generated per
machine, and a fresh CI runner makes a new one on every build, so every APK came out
signed with a different key and Android refused to install it over the previous one:
each update meant uninstalling first, which wipes your alarms. Pinning one key fixes
that. It is a debug key with the conventional `android` password and is worth nothing
for distribution — but it does mean anyone with this repo can build an APK that installs
over yours, so a real release would need its own keystore kept out of version control.

`versionCode` comes from the CI run number, so a newer build always supersedes an older
one.

To cut a new release, push a tag:

```
git tag v0.1.1 && git push origin v0.1.1
```

CI builds the APK and attaches it to the release automatically.

## Building locally

Requires Android SDK 35 and JDK 17+.

```
./gradlew assembleDebug
```

### Bumping dependencies

`gradle/libs.versions.toml` is pinned to a known-mutually-compatible set (AGP 8.7.3 /
Kotlin 2.0.21 / Compose BOM 2024.12.01) rather than the newest of each. To move forward,
bump AGP and Kotlin together, then the Compose BOM, and let the Gradle wrapper follow
AGP's minimum.

## Layout

| Path | What lives there |
| --- | --- |
| `data/Model.kt` | `AlarmRule`, `PrayerAnchor`, `PrayerSettings` |
| `data/AppStore.kt` | JSON-in-DataStore persistence |
| `prayer/PrayerEngine.kt` | adhan wrapper; civil date in, instants out |
| `alarm/AlarmMath.kt` | next-occurrence search — pure, no Android, fully tested |
| `alarm/AlarmScheduler.kt` | all AlarmManager interaction |
| `alarm/AlarmReceiver.kt` | fires the service, re-arms the rule |
| `alarm/AlarmService.kt` | ringing: audio, vibration, wake lock, notification |
| `alarm/AlarmActivity.kt` | full-screen ringing UI |
| `ui/` | Compose screens and view model |
