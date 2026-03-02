# AGENTS.md

## UI Styling

**Approach:** Pure XML + shape drawables. No MDC or external UI framework.  
If MDC is adopted later, these components map directly to Material3 equivalents.

---

### Color Palette (`res/values/colors.xml`)

| Name | Hex | Usage |
|---|---|---|
| `colorPrimary` | `#1A1A1A` | Primary text |
| `colorPrimaryDark` | `#C62828` | Status bar (darker red, matches action bar) |
| `colorAccent` | `#E53935` | Buttons, active states, error text |
| `colorBackground` | `#F5F5F5` | Screen backgrounds |
| `colorSurface` | `#FFFFFF` | Cards, input fields |
| `colorTextPrimary` | `#1A1A1A` | Body text, titles |
| `colorTextSecondary` | `#757575` | Subtitles, section labels, sync timestamps |
| `colorTextHint` | `#BDBDBD` | Input placeholder text |
| `colorEnabled` | `#2E7D32` | ON badge text (green) |
| `colorEnabledBg` | `#E8F5E9` | ON badge background |
| `colorDisabled` | `#9E9E9E` | OFF badge text (grey) |
| `colorDisabledBg` | `#EFEFEF` | OFF badge background |
| `colorDivider` | `#EEEEEE` | Horizontal divider lines |
| `colorInputBorder` | `#DDDDDD` | Input field border stroke |
| `colorIconCircle` | `#FFF0F0` | Icon circle background on feature tiles |
| `colorWarningBg` | `#FFF3CD` | Permission / warning banners |
| `colorWarningText` | `#856404` | Warning banner text |

Always reference `@color/...` — never hardcode hex values in layouts.

---

### Cards

```xml
app:cardCornerRadius="16dp"
app:cardElevation="0dp"
app:cardPreventCornerOverlap="false"
app:cardUseCompatPadding="false"
app:cardBackgroundColor="@color/colorSurface"
```

- **No elevation** — white card on `#F5F5F5` background provides natural depth through contrast
- **16dp radius** — soft, rounded feel
- `cardPreventCornerOverlap="false"` + `cardUseCompatPadding="false"` — prevents compat shadow artifacts at corners
- Inner padding: `20dp` for content cards, `16dp` for list items

---

### Buttons

| Type | Drawable | Text color | Usage |
|---|---|---|---|
| Filled (primary CTA) | `@drawable/bg_btn_filled` | `#FFFFFF` | Save, confirm actions |
| Outlined (secondary) | `@drawable/bg_btn_outlined` | `@color/colorAccent` | Test, Sync |

Both drawables are `selector`-based — darker on press, transparent/light press state.  
Standard height: `40dp`. Padding: `20-24dp` horizontal.

---

### Input Fields

Wrap `EditText` in a `LinearLayout` with `@drawable/bg_input` background:

```xml
<LinearLayout
    android:background="@drawable/bg_input">
    <EditText
        android:background="@android:color/transparent"
        android:paddingStart="14dp"
        android:paddingEnd="14dp"
        android:paddingTop="12dp"
        android:paddingBottom="12dp" />
</LinearLayout>
```

`bg_input.xml`: white fill, `#DDDDDD` 1.5dp stroke, 8dp radius.

---

### Section Labels

Used above logical groups (e.g. "QUICK ACCESS", "SERVER ENDPOINT"):

```xml
android:text="SECTION TITLE"
android:textSize="11sp"
android:textStyle="bold"
android:letterSpacing="0.08"
android:textColor="@color/colorTextSecondary"
```

---

### ON/OFF Badge (alarm state)

Set in `AlarmAdapter.kt` — switches both text color and background drawable:

```kotlin
holder.tvEnabled.text = if (alarm.enabled) "ON" else "OFF"
holder.tvEnabled.setTextColor(if (alarm.enabled) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
holder.tvEnabled.setBackgroundResource(if (alarm.enabled) R.drawable.bg_badge_on else R.drawable.bg_badge_off)
```

Pill padding: `12dp` horizontal, `5dp` vertical. `bg_badge_on`: green `#E8F5E9`, `bg_badge_off`: grey `#EFEFEF`, both 12dp radius.

---

### Feature Tiles

Icon circle container — 52×52dp, `@drawable/bg_icon_circle` background (`#FFF0F0` oval):

```xml
<LinearLayout
    android:layout_width="52dp"
    android:layout_height="52dp"
    android:background="@drawable/bg_icon_circle"
    android:gravity="center">
    <TextView android:textSize="24sp" />  <!-- emoji icon -->
</LinearLayout>
```

Label below: `15sp`, bold, `@color/colorTextPrimary`.

---

### Icons — Always Use the Helper Script

**⚠️ Never add icons manually.** Always use `tools/get-icon.sh` to download and convert icons.

```bash
# From the project root:
bash tools/get-icon.sh <icon_name>              # rounded (default)
bash tools/get-icon.sh <icon_name> outlined     # outlined style
bash tools/get-icon.sh <icon_name> sharp        # sharp/angular style

# Examples:
bash tools/get-icon.sh alarm
bash tools/get-icon.sh bar_chart rounded
bash tools/get-icon.sh check_circle outlined
```

- Source: **Material Symbols** (Google) — browse at https://fonts.google.com/icons
- Icon names are lowercase with underscores (e.g. `bar_chart`, `alarm_on`, `check_circle`)
- Output: `app/src/main/res/drawable/ic_<name>.xml` — pure Android vector drawable
- Only downloaded icons end up in the APK — no bundle bloat
- Default style is **rounded** (consistent with our soft card aesthetic)

**Use in XML layouts:**
```xml
<ImageView
    android:layout_width="24dp"
    android:layout_height="24dp"
    android:src="@drawable/ic_alarm"
    android:tint="@color/colorAccent"
    android:contentDescription="..." />
```

**Icons in use:**
| File | Icon | Used in |
|---|---|---|
| `ic_alarm.xml` | alarm (rounded) | Alarms tile |
| `ic_bar_chart.xml` | bar_chart (rounded) | Metrics tile |
| `ic_sync.xml` | sync (rounded) | Available for sync buttons |
| `ic_warning.xml` | warning (rounded) | Permission banner |

---

### Drawables Reference

| File | What it styles |
|---|---|
| `bg_input.xml` | Input field — white fill, border, 8dp radius |
| `bg_btn_filled.xml` | Primary button — red fill, press darkens |
| `bg_btn_outlined.xml` | Secondary button — red stroke, press tints bg |
| `bg_badge_on.xml` | ON badge — green `#E8F5E9`, 12dp radius |
| `bg_badge_off.xml` | OFF badge — grey `#EFEFEF`, 12dp radius |
| `bg_icon_circle.xml` | Tile icon circle — `#FFF0F0` oval |

---

## Versioning

This project follows **SemVer** (`MAJOR.MINOR.PATCH`) with one alpha-stage exception:

- **Current stage:** Alpha — API and behaviour are unstable
- **MAJOR** stays at `0` until the project exits alpha
- **MINOR** bumps for breaking changes (during alpha, minor = what major would be in stable)
- **PATCH** bumps for backwards-compatible fixes and small additions
- **Examples:**
  - Breaking change → `0.1.0` → `0.2.0`
  - Bug fix / new feature (non-breaking) → `0.1.0` → `0.1.1`

Tag format: `v<MAJOR>.<MINOR>.<PATCH>` (e.g. `v0.1.0`)
