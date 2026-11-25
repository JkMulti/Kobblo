# Kobblo Design

Kobblo's design system, modeled on the iOS Files app: a system-gray canvas, white inset groups
with hairline separators, large titles, filled blue folders, and one blue tint. Code lives in
`ui/design/`.

The direction comes from Refero research. Refero has no entry for Apple's Files app, so the
references are the system document browser as it appears inside other apps (Miro, Bear, Freeform,
Asana, Figma), plus Telegram for inset grouped lists and Freeform for context menus.

This is an original Android design in that style, not a copy. The icons and file art are our own
drawings: SF Symbols are licensed for Apple platforms only, so they must never ship here.

## Structure

The tabs are **Home**, **Recents**, **Browse**, **Send**, and **Settings**. The app opens on Home, and
Back from any other tab returns to it. There is no drawer and no floating action button.

- **Home** is every photo and video on the device as one feed, newest first: an edge-to-edge grid
  under headers for the recent days and then each month, with All, Photos, Videos and Audio as tabs that a sideways swipe also moves between, each keeping its own place. Its toolbar
  has Search and Albums on every tab, Audio too, so it doesn't change as the pages turn. An album can be kept off Home from its menu, which changes nothing about the files.
- **Recents** is what you touched lately, grouped by when, in the same grid as a folder.
- **Browse** opens on the list of locations (`ExpressiveLocationsScreen`, the `"locations"`
  location), with sections for Locations, Favorites, Categories, and Tools. Everything else that
  isn't a file hangs off this list, the Vault included: it's the last of the Tools, and hiding it
  hides that row. Tapping the tab while on it returns here; switching back from another tab restores
  the folder you left, except cloud folders, which need their folder stack. Recently Deleted, the
  cleaner and the Vault close when another tab is picked, but stay under Settings.
- **Folders** have a Back button naming where it goes, `+` to create, and `...` for view options.
- **Settings** is everything about the app itself, on one page: Permissions and Smart Features first,
  then General (language, vibration), Appearance, Open With, Recently Deleted (whether it keeps files,
  and for how long), Organize Downloads, Privacy & Security, Send & Receive, and About, which opens
  the About page within the tab and shows the last crash report when there is one.
- **Privacy and security:** Kobblo has its own lock, a PIN, pattern or password apart
  from the phone's screen lock, in `security/`. It guards locked items and App Lock, while the Vault keeps
  its own password, so getting past App Lock doesn't open it. Only a salted Argon2id hash is stored; a
  password from an older version still opens and is upgraded, and the old 0000 default is gone. Every
  fifth wrong try in a row starts a pause, longer each round. Fingerprint or face can stand in for the
  lock, strong biometrics only, never the screen lock, and turning it on asks for the lock. A forgotten
  lock is reset once the phone's screen lock vouches for it. App Lock covers every screen, the file
  picker and the photo editor too, when the app opens and after it's been away for the chosen time, and
  blanks its card in recent apps on Android 13 and later. While locked, the app drops what it was showing,
  so no sheet can sit above the lock, except the editor, which keeps an edit under it. Turning App Lock
  off asks for the lock. The lock screens, the Vault and anything locked can't be captured unless
  Allow Screenshots is on. The Vault's files are encrypted at rest, names included, and its fingerprint
  unlock is a key in the Keystore of its own. Its password gets the same wrong-try pauses as Kobblo's
  lock, and it locks when the screen turns off and after Lock Vault After in Settings: Immediately, 1, 5
  or 15 minutes away. A file picker or share sheet the app opened itself gets a minute's grace from both
  App Lock and the Vault, so what was picked isn't dropped at a locked door.

Holding any file opens its menu, as on iOS. Select is in that menu, and in a folder's `...` menu.
Tapping the thumbnail in a list row selects directly. In a folder the menu waits for the finger to lift,
because holding and moving instead selects a run of files. While selecting, a drag that starts sideways does
the same, as in the photo grid, and tapping the count offers everything else: all, none, invert, by kind.

**Carrying a selection.** Holding something already selected picks up all of it, wherever it's selected: the held item lifts,
the rest fly in from where they are, and the stack follows the finger. Moving before the hold is up is still a sideways run or a
scroll, so the hold is what tells them apart, and holding what isn't selected still opens its menu. While carrying:

- Let go on a **folder** to move everything there, by the same job as Cut and Paste. Hold still on one for 0.7 s and it opens, while passing over one on the way somewhere never does, with the
  drag still going, so you can go deeper. **Back** and the **path bar** do the same for their folders. Once something's opened, the
  finger has to go somewhere before anything else will, so a finger left in one place doesn't keep falling through folders.
- Once a hover has opened a folder, letting go anywhere on its list moves things into it.
- Near the top or bottom of a list, it scrolls.
- The **selection bar stays**, and each action lights up under the stack. Letting go on one does exactly what tapping it does,
  including asking first for Delete. That's what makes it useful on Home and in albums, which have no folders. If a hover has
  gone to another folder by then, it comes back first, since the selection means this folder's list.
- Let go anywhere else, turn the phone or put down a second finger, and everything flies back, with the app back in the folder it
  started in. Nothing's been changed until something takes the drop.

**Search** is the field already at the top of each list. Its filters (scope, kind, size, date) appear only
once it has the cursor, and read as their current value. Results are one inset group, and every row names its
folder. They stay behind whatever is opened from them, so Back returns to them.

**Search on Home** is the one place search is a button rather than a field, because a field there would push every
photo down for something most visits don't need. It opens a screen with one field and no filters. Every word typed has
to fit, and each only has to fit some way: a name, a folder, a date, a kind, a size, a camera, a place or a tag. Until
something's typed, it offers what's really in this library as capsules, each just a word that could have been typed.

A folder keeps its own sort and view, and passes them to folders inside it that haven't chosen. Tapping the
sort that's on flips it, which is why there's no separate direction control.

**Welcome.** A new install opens on three steps over the app: what it does, the access it needs, and Smart
Features where the phone can run them. Access is switches in two groups, Needed and Optional, as a phone's own
privacy screens are: files, then notifications and photo locations. A switch the app can't turn off itself opens
the system's page instead, and one refused for good says where to allow it. The last step shows the newest photos,
loosely tilted and drifting, and Let's Go flies each into its own tile in the Home grid underneath. Settings has
the same switches under Permissions.

**Smart Features** find photos by what's in them, read their text, and put each person's photos together, all on
the phone. Each is a switch with what it downloads, and nothing downloads until it's on. Search
results it finds come after the exact matches, under their own heading, best first. People sit under Albums as a
row of round faces, each opening as an album that can be named. A photo with words in it shows a Text button at
its corner, which opens the words to select, copy or share. They need Android 11, and below that they don't appear.

## Viewers

Viewing a photo follows a photo library. It is white with the controls showing, in light mode, and
black when a tap hides them along with the system bars. The top bar shows the date and time, a strip
of slivers holds every photo in the folder, and a floating glass toolbar holds the actions.

- **Opening** grows the photo out of whatever it was tapped in: a tile on Home, a card in a folder, a row's
  rounded thumbnail in Recents or a list, keeping that corner until it's full size. The viewer is built
  only once it fills the screen, so building it never stutters the movement. Its controls fade in after.
  Lists keep up as photos are swiped through, so putting the viewer away lands each in its own row.
- **Sideways:** the next photo. **Up:** its details, which shrink the photo into the space above
  them. **Down:** closes it. The photo follows the finger in any direction, shrinking around where the finger
  touched, and letting go flies it back into its tile at the speed it was thrown, while its tile stays empty
  until it lands. Back, and the system's back gesture as it's drawn, do the same. Anywhere without a tile, it
  shrinks and fades where it is.
- **The strip** follows the photos exactly, even halfway through a swipe: the sliver nearest the middle
  widens as it arrives and its neighbours make room, so it never jumps. Dragging it scrubs through the
  photos with a light tick for each, and tapping one shows it and glides there.
- **Pinch** zooms around the fingers and **double-tap** zooms into the tapped spot. A zoomed photo
  takes the whole screen, and panning stops at the photo's edges rather than the screen's.

Videos share the pager, top bar, toolbar, and gestures with photos, so a feed swipes straight through
both. A video's scrubber sits above the strip, which stays where it is on every page. Its
controls step aside after three seconds of playing, and come back when it ends. A double tap seeks ten
seconds by side instead of zooming. Everything else, such as speed, loop, subtitles, and Picture in
Picture, is in the `...` menu, and appears only when it applies.

A video's gestures follow one rule: **with the controls up, drags belong to the viewer, and with them hidden, someone is
watching, so drags belong to the player.** Hidden, a drag up or down on the left is brightness, for this window only, and on
the right is volume, and a sideways drag seeks. A touch starting within 32dp of the sides or 56dp of the top and bottom is
left for the system's own gestures. Holding plays at double speed and a pinch fills or fits, with the controls up or not.
What's changing shows in a dark capsule, and subtitles and those capsules are the only things drawn on the picture itself.

Photos that can do more wear a quiet glass badge under the top bar: **Motion**, **Panorama**, or a page
stepper. Holding a motion photo also plays it. Badges live with the chrome rather than on the page,
because glass can't sample a layer it's drawn inside of.

Details open calm, with a short summary, and **Show All** reveals every group in the same panel. A file
that can't be shown says so with `KobbloMessage` and offers **Open With**, never a blank page.

Anything else opened from a row or card, a document, a text file or a font, slides in from the right like any
other screen, and off to the right when closed. Documents use the same top bar and toolbar through `ViewerTopBar`, `GlassCircle`, and `ToolbarAction`,
with their details in a sheet, because a page has its own vertical swipe.

**PDFs** are one continuous column of pages, each as wide as the screen at normal size, and any size of file opens at once
and comes back to where it was left. A tap hides everything but the pages, as does moving them by hand. While they move, a
thin handle on the right edge says which page this is, drags through the whole file, and offers Go to Page if its label is
tapped. Search replaces the top bar and marks every match with the tint. Holding a word selects it. The toolbar has Search,
Contents when the file has any, and the rest is in the `...` sheet. Dark Pages turns white paper dark and leaves hues as they
are, so photographs stay recognizable.

The **mini player** is a glass capsule above the tab bar, with the same margins and material.
Playback fills it from behind. Tap it to open the player, and swipe it sideways to stop.

The **full player** follows a music app's Now Playing, on a background made from the cover's own
colors, so its text is white in either theme. The cover settles back when paused, and sliders swell
under the finger. Pull the screen down to close it. Its bottom row opens lyrics, in the cover's place,
and Up Next, in a sheet. Sound settings live in its `...` sheet, so the player itself stays clean.

**Audio** is the Home tab's fourth filter, and it is the music library: Songs first, with capsule chips
for the other views staying above the list. Shuffle is the filled button at the head of a list, and
holding a song opens its menu like any other file. On the player's dark background and nowhere else,
music text and controls are white whatever the theme.

## Rules that keep it Kobblo Design

1. **A token's role is part of the token.** If a color has a role below, don't use it for anything
   else. Reach for a new token only if nothing fits, and add its role here.
2. **Rows live in groups.** A list is white rows in an inset group on the gray canvas: rounded only
   at the group's outer corners, with a hairline starting under the title. Use `KobbloGroup` and
   `KobbloRow`, or `Kobblo Design.shapes.groupItem(index, count)` in a lazy list. Icon grids sit directly
   on the canvas, with no cards behind them.
3. **Every corner is Kyant.** Use `Kobblo Design.shapes`, `CapsuleShape`, or `RoundedRectangle`. Never
   `RoundedCornerShape` or `CircleShape`. Material's theme shapes already draw Kyant curves through
   `KyantCornerShape`, and `ui.design.Button` replaces Material's buttons.
4. **Every icon is `AppIcons`.** Never add `material-icons`. See Icons.
5. **The ambient shadow is the only shadow.** Use `Modifier.ambientShadow`, on floating things only.
6. **Motion earns its place.** It gives feedback, keeps continuity, or shows hierarchy. If it does
   none of those, remove it. See Motion.
7. **Sheets have one frame.** Use `KobbloSheet`, which brings the grabber, corners and canvas. A sheet
   has no Close button: dragging down, tapping outside and Back all close it. Its title, when it has
   one, is `KobbloSheetTitle`: small and centered.
8. **Questions read one way round.** Cancel on the leading side and the action on the trailing side, with
   `KobbloActionPair` at a sheet's foot. Anything that destroys something asks with
   `ExpressiveConfirmationSheet` and its button is red. An alert, for a name or a short notice, uses
   `KobbloAlertCancel` and `KobbloAlertAction`. A whole screen left without finishing, such as the lock
   screen or a folder picker at its top, closes with a cross: `KobbloIconButton` with `AppIcons.Close`,
   where the back button would sit, and "Cancel" kept as its spoken label.
9. **Nested corners are concentric.** A shape inside another's corner rounds by the outer radius less
   the gap between them, so the two curves stay parallel: outer 28 with an 8dp gap is 20 inside, never 28
   or 14. Use `KobbloShapes.concentric(outer, inset)` or `concentricRadius`. Keep the gap even at the side
   and the foot, or no radius fits both. A capsule inside a capsule with an even gap already fits. A round
   button at a capsule's end sits on the center of that end, and a round mark in a thumbnail's corner sits
   `concentricInset(corner, radius)` in, so the two share a center. A row's badge sits 12dp inside a
   group's 20dp corner with its own 8dp corner, so it fits too.
10. **Words.** Actions and row titles are in Title Case and statements are sentences. Say what happened
   in plain words, with no exclamation marks, no capitals for emphasis, and never an exception's own
   text. A menu's glyphs are `ink`, or `danger` on a destructive row; the tint is for what's on or what
   acts on its own. A top bar's icons are `ink`, black in light and white in dark, back chevron included,
   while its words, like Select or Done, keep the tint. Back is a round toolbar button like the others,
   `KobbloBackButton`, with nothing written beside it. TalkBack still names where it goes ("Back to
   Home").

## Color

Read from `Kobblo Design.colors`. Light, dark, and AMOLED all resolve automatically. Dark already sits on
true black, so AMOLED deepens the groups instead.

| Token | Role |
|---|---|
| `canvas` | Page background: the gray that groups sit on |
| `snow` | Grouped rows, cards, and round toolbar buttons |
| `mist` | Search fields, pressed rows, segmented control tracks |
| `silver` | Control outlines |
| `ink` | Primary text and default icons, and every icon in a top bar |
| `smoke` | Secondary text and metadata |
| `pewter` | Placeholders, chevrons, and disabled content only. Too low contrast for reading |
| `action` | The tint: actions, links, selection, and location icons. The owner picks it in Settings |
| `danger` | Destructive actions and critical warnings |
| `success`, `magenta` | File-type indicators only |
| `glass` | Fill of floating glass controls |
| `hairline` | Separators between grouped rows |
| `scrim`, `ambient` | As named |

`smoke` is deliberately a step darker than iOS's secondary label, so metadata stays readable on
white.

**The tint** is one of `KobbloAccent`'s seven: Blue, the default, then Indigo, Purple, Pink, Orange,
Green, and Teal, picked under Appearance in Settings and saved as `tint_color`. `withAccent` puts it
in `action` for light, dark, and AMOLED alike, and the file picker and photo editor read it too. Each
reads at 3:1 or better as text on the page and under white text, which is why orange, green, and teal
are deeper than Apple's. Red is left out because it already means destructive, and yellow because no
yellow reads on white. Badges, category glyphs, file-type colors, folders, and the switch's green
don't follow the tint.

`KobbloTint` holds the colors behind settings badges and category glyphs. They label things and
never act, so a tinted badge is not a button.

`BrandMark` is the launcher icon itself: Browse's blue folder with a landscape of blue hills and a white sun along its foot, on a
white tile. It draws the icon's own layers, so the welcome, About and Settings always show what's on the
home screen. The icon is generated by `scripts/app-icon/build_icon.py`, which also writes the themed
(monochrome) layer and the legacy images.

**File-type indicators** tint a document's glyph: images indigo, videos pink, audio orange, documents
blue, apps green, downloads teal, archives brown, large files gray. Read them from
`ExpressiveTokens.categoryTint`.

## Type

Read from `Kobblo Design.type`. The device's own font is deliberate: it's what makes the app feel native
on every phone. Hierarchy comes from weight and tight tracking at display sizes.

The one exception is `code` (20, medium, monospace), for values read one character at a time, such
as a recovery key.

| Style | Size | Use |
|---|---|---|
| `display` | 54, bold | Hero numbers |
| `largeTitle` | 34, bold | Screen titles |
| `title` | 22, bold | Section titles |
| `headline` | 17, medium | Toolbar and sheet titles (semibold), button labels |
| `body` | 17 | Row titles and reading text |
| `callout` | 15 | Values at the end of a row |
| `footnote` | 13 | Row metadata, grid names, text beneath a group |
| `caption` | 12, medium | Grid metadata and labels |
| `tab` | 11, medium | Tab bar and selection bar labels |

## Icons

`AppIcons` is our own set, drawn on a 24 grid with a 1.7 stroke and round caps and joins throughout.
Each glyph is one or two lines of SVG path data in `ui/design/icons/AppIcons.kt`, so adding one
doesn't need an asset pipeline. Keep the grid, the stroke, and the round terminals, and check a new
glyph at 24dp and at 16dp next to its neighbours.

- **`FolderOpen`** stands for your files as a whole, and for showing something in its folder. `Folder`
  is a folder itself: a row, a tile, the Browse tab.
- **`Fill` variants** mark a selected or active state, such as the current tab. Line is the default.
  A fill keeps its line version's outer size, so nothing shrinks when it is selected, and earns its
  detail with a cut: a folder's gap, a plane's crease, a lock's keyhole, a clock's hands.
- **`Play`, `Pause`, `MoreHoriz`, and similar** are solid by nature and have no line version.
- **`mirror = true`** on directional glyphs flips them in right-to-left languages.

`FileArt.kt` holds the two illustrations: `FolderIcon`, the two-toned blue folder, which badges
well-known folders such as Downloads, and `PaperIcon`, a sheet of paper for documents with no
preview. They draw their own colors, so they are `Image`s rather than tinted icons.

## Space and shape

Every space is a step of 4dp. 2dp and 6dp are only for nudging text and glyphs inside small controls. Each
kind of space has one value, from `KobbloSpace` and `KobbloSize`:

| Space | Value | Where |
|---|---|---|
| `screen` | 16 | A screen's side margin. Large titles, search fields, section titles and the edges of groups and cards all start on this line |
| `inset` | 12 | Inside a group, card or bar, content sits this far from every edge it meets: the top and bottom as much as the sides |
| `content` | 28 | Where text inside a group starts, from the screen's edge. Notes above and below a group start here too |
| `leadingGap` | 12 | Between a row's badge or picture and its text |
| `section` | 24 | Above a section's title |
| `titleGap` | 8 | Between a section's title and its group |
| `groupGap` | 16 | Between two groups with no title between them: as much canvas above and below a group as beside it |

| Size | Value | Why |
|---|---|---|
| `badge` | 32 | A settings row's badge |
| `row` | 56 | A badge with the inset above and below, so it sits as far from a group's top as from its side. Every one-line row is this tall, and two lines of text still fit |
| `thumbnail` | 40 | The picture leading a row of files, songs or apps |
| `fileRow` | 64 | The same for a thumbnail |
| `toolbar` | 52 | The bar at the top of every screen, with 40dp round buttons |
| `KobbloActionHeight` | 52 | Every full-width button |

Rows keep 8dp above and below their text, rather than the inset, since text carries its own space above and
below its letters. Their minimum height puts the badge or picture at the inset.

A floating panel, such as a dialog, has more room: 24dp inside a 28dp corner.

Lists scrolling under the tab bar reserve `KobbloSpace.tabBarClearance` at the bottom.
Lists of rows stop at `KobbloSpace.readableWidth` and center on a tablet or a phone on its side, through
`Modifier.kobbloReadableWidth`. Grids of pictures still go edge to edge.

| Shape | Radius | Use |
|---|---|---|
| `capsule` | Full | Every button, chip, search field, and tab |
| `xs`, `sm` | 8, 12 | Small controls, grid thumbnails |
| `group` | 20 | Inset groups: a badge's 8dp corner plus the 12dp inset |
| `md`, `lg` | 16, 20 | Surfaces |
| `xl` | 28 | Floating panels |
| `sheet` | 28, top only | Bottom sheets |
| `tile` | 24% of the short side | Icon badges and list thumbnails, at any size |
| `concentric(outer, inset)` | `outer - inset` | Anything inside another shape's corner. See rule 9 |

## Motion

Use `KobbloMotion`, never ad-hoc numbers. Everything respects the system's reduced-motion setting
through `ExpressiveMotion.isReducedMotion()`, and every animation stays interruptible.

| Token | Value | Use |
|---|---|---|
| `press()` | Spring, instant feel | Press feedback through `Modifier.pressScale` |
| `snappy()` | Spring, damping 0.75, stiffness 400 | Tab capsule, switches, selection marks |
| `smooth()` | Spring, damping 0.85, stiffness 280 | Screens and large movement |
| `fadeIn()`, `fadeOut()` | 200ms out, 120ms in | Appearing and disappearing |
| `state()` | 200ms | Color and state changes |

The signature moments:

- **Tab bar:** one capsule travels between tabs, squeezing slightly in flight. On arrival the tab's
  glyph fills in and gives one small hop.
- **Going deeper:** the new screen or folder arrives from the edge over the old one, which slides a
  quarter of the way back and dims while still showing its own files. Going back retraces that path,
  the top screen sliding off to the right over the one it uncovers. Every screen moves this way: folders,
  albums, music, tools, settings, a document or any other file opened from a row. The one exception is
  a photo or video, which flies out of its tile. Tabs are siblings and only use a short parallax.
- **Large title:** the title and search field fold away as content scrolls, the small title fades
  into the toolbar, and both return at the top.
- **Lists:** rows settle in with a fade and an 8dp rise, staggered and capped, so a long list never
  makes anyone wait. See `Modifier.kobbloAppear`.
- **Sections:** a section of the Browse list folds away while its chevron turns.
- **Selection:** every item shows a round mark, the check pops in when chosen, and the count rolls
  with each tap.
- **Carrying:** the held item lifts and grows a little under a soft shadow, and the others fly out from where they
  really are, one just after another, to form a deck, one card behind another, each a little smaller, fainter and further to one side, leaning toward the middle of the screen, with the true total in a tint badge.
  Each card chases the one in front on a spring, so a quick move strings them out and they settle when the finger
  stops, while the top card never leaves the finger. The originals stay as ghosts so nothing jumps. Let go on a target
  and the pile shrinks into it, which gives one pulse. Anywhere else, the cards fly back and land on their originals,
  or fade if those have scrolled away. Reduced motion is just lift, move and drop: no stagger and no trail.
- **Rows:** a pressed row fills at once and fades out gently, rather than scaling.
- **Photo flight:** a copy of the photo travels between its tile and the viewer on springs, its crop opening
  from the tile into the whole photo and its corner squaring off. A flick that drifts sideways is still a pull,
  because the viewer sees it before the pager does. Position and size move separately, so a throw carries on the way
  it was thrown before curving home, and the size never overshoots. See `gallery/PhotoFlight.kt`.
- **Welcome entrance:** the words step aside, then the photos fly to their tiles nearest the tap first, so the
  grid fills like a wave as the backdrop fades.
- **Send:** waves leave your device in a steady rhythm, each with a soft inner glow and an edge that
  slowly shifts color. Your avatar beats as each is born, nearby devices light up as a wave reaches
  them, and turning receiving off settles the waves into still rings.

## Components

Before building something new, check `ui/design/`.

| Component | Use |
|---|---|
| `KobbloGroup`, `KobbloGroupHeader`, `KobbloGroupFooter` | An inset group and the text around it |
| `KobbloRow` | A row in a group, with optional leading, trailing, and subtitle |
| `KobbloIconBadge` | A white glyph on a colored tile, as in a settings list |
| `KobbloChevron` | A row's disclosure hint |
| `KobbloSheet`, `KobbloSheetTitle` | Every bottom sheet, and its title |
| `KobbloActionPair` | Cancel and the action, at the foot of a sheet |
| `ExpressiveConfirmationSheet` | Asking before something that's hard to take back |
| `KobbloAlertCancel`, `KobbloAlertAction` | The buttons of an alert |
| `KobbloLoader`, `KobbloLoading` | The spinner, and the spinner filling a list's place |
| `KobbloIconButton` | Round toolbar and inline icon buttons |
| `KobbloSearchField` | Search |
| `KobbloTextField` | A one-line field for a name, a path or a password |
| `KobbloFormField` | A labelled field as a row of a group, for a form with several |
| `GradientAlertDialog` | Every alert: a floating panel with its title, text and actions. The name is older than the look |
| `KobbloSegmentedControl` | A few short, mutually exclusive options |
| `StorageMeter` | Used and free space |
| `KobbloMessage` | Empty and blocked states |
| `KobbloFileRow`, `BrowseGridCard` | Every file list and icon grid |
| `SelectionCircle` | The round mark on an item while selecting |
| `Modifier.kobbloGlass` | Floating controls, with a fallback below Android 12 |
| `rememberLargeTitleState` | A large title that folds on scroll |
| `BrandMark` | The launcher icon, in the app |
| `Modifier.flightCell` | A photo tile the viewer opens out of and closes back into |
| `AccessGroups` | The permission switches, in the welcome and in Settings |

## Older names

`ExpressiveTheme.colors`, `CompatColors`, and `MaterialTheme.colorScheme` all read Kobblo Design's tokens,
so older screens stay in the system. New code should use `Kobblo Design.colors` directly.
