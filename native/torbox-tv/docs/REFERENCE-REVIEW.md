# MovieBox reference review

The initial attachment transfer failed. A previously saved copy was later recovered and inspected on 2026-10-10 UTC. This review uses that actual APK, not screenshots or assumptions from its filename.

Reference: `Premium_MovieBox_v3.0.10.1110.03_Mod.apk`, version `3.0.10.1110.03`, package `com.community.oneroom`, 70,336,029 bytes. SHA-256: `2b8998a3ae2d1de635cbe59d8c8e2d8d4acb374478f06dc0401671980265346e`.

## Inspection limits

Only ZIP metadata, the compiled manifest/resource table, and selected compiled layout XML were examined with Python's ZIP reader and Android `aapt2`. The APK was not installed or executed. No application code was decompiled or adapted. No binaries, fonts, artwork, logos, trademarks, or layout XML from the reference were copied into TorBox TV. Resource dumps remain temporary inspection material outside the repository.

Compiled resources reveal available layout structures and values. They cannot establish which views are currently enabled, server-supplied content, runtime screen composition, actual animation behavior, or the complete focus/navigation flow. No runtime screenshot match is claimed.

## Observed navigation and structure

`activity_main` uses a bottom-constrained Material `TabLayout`, with an 80 dp layout height. The supplied reference is a mobile/portrait application: its manifest declares portrait screen and touch-oriented features. It does not establish a Fire TV sidebar or remote navigation design.

The resource table contains primary tab labels Home, Downloads, Me, Premium, ShortTV, and Video. Category labels include Movie, TV/Series, Animation, Trending, Music, and Education. These labels are available strings, not proof that every category appears in a particular user session.

`fragment_home` contains a `ViewPager2` and a top search container. `fragment_movie` contains category/filter controls and a `RecyclerView` for the movie list. Separate layout resources exist for search suggestions/results, movie details, seasons, episode lists, subtitles, and player settings.

The inspected title-information layout includes a shaped movie-cover image and cover mask, title, score/time row, description, and subtitle metadata. It uses a clear visual hierarchy rather than presenting source infrastructure. Banner/card resources include rounded containers, artwork, title/description text, and a play affordance. Resource names establish banner and recommendation components; they do not prove a particular runtime carousel arrangement.

## Observed dark palette and typography

| Resource | Observed value |
|---|---|
| Dark base `gray_dark_00` | `#101114` |
| Raised surface `gray_dark_10` | `#1C1E21` |
| Secondary surface `gray_dark_20` | `#28292E` |
| Further surface `gray_dark_30` | `#383A40` |
| Muted text `gray_dark_60` | `#9BA0A8` |
| `brand`, night configuration | `#10A84D` |
| `brand`, default configuration | `#07B84E` |
| Primary text `text_01`, night | White |
| Secondary text `text_02`, night | White at 80% opacity |

The APK bundles font files named Roboto Regular, Roboto Bold, and Audiowide Regular. File presence does not prove their use on every screen. Inspected mobile title text uses 18 sp and smaller metadata uses 12 sp. These mobile dimensions are unsuitable as direct television typography targets.

## Original TV adaptation

TorBox TV should borrow the high-level emphasis on artwork, dark surfaces, uncluttered metadata, accessible discovery, and prominent viewing actions. Its sidebar, larger television text, remote focus treatment, home priorities, and independent native playback are original adaptations of the user's TV requirements. The reference's download/social/premium sections and mobile bottom tabs are not a required feature map for this application.

This review supplies reference evidence without asserting that TorBox TV reproduces MovieBox's runtime appearance or proprietary implementation.
